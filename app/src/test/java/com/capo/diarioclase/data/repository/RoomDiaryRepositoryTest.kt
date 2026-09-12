package com.capo.diarioclase.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.CerLevel
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.SessionEntity
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.SessionState
import com.capo.diarioclase.diary.DiarySaveResult
import com.capo.diarioclase.processing.evidence.InterpretationMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class RoomDiaryRepositoryTest {
    private lateinit var database: DiarioDatabase
    private lateinit var repository: RoomDiaryRepository
    private var nowEpochMs = 2_000L

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DiarioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomDiaryRepository(database, Clock { nowEpochMs }, IdProvider { "entry-1" })
    }

    @After fun tearDown() = database.close()

    @Test fun `saving and rereading preserves every permanent visible field`() = runTest {
        insertSession()

        val saved = repository.saveVerified(SessionId("session-1"), draft())

        val verified = saved as DiarySaveResult.Verified
        assertEquals("2026-09-12", verified.entry.pedagogicalDate)
        assertEquals(CerLevel.B2, verified.entry.level)
        assertEquals("Conectores", verified.entry.topics)
        assertEquals("Debate guiado", verified.entry.activities)
        assertEquals("42-43", verified.entry.pages)
        assertEquals("3 y 4", verified.entry.completedExercises)
        assertEquals("Escribir un texto", verified.entry.homework)
        assertEquals(verified.entry, repository.getBySession(SessionId("session-1")))
    }

    @Test fun `search ignores case and accents`() = runTest {
        insertSession()

        repository.saveVerified(SessionId("session-1"), draft(topics = "Conectores concesivos"))

        assertEquals(1, repository.observeEntries("CONCESÍVOS").first().size)
    }

    @Test fun `search covers date level and every visible field`() = runTest {
        insertSession()
        repository.saveVerified(SessionId("session-1"), draft())

        listOf("2026-09-12", "b2", "conectores", "debate", "42-43", "3 y 4", "texto").forEach { query ->
            assertEquals(query, 1, repository.observeEntries(query).first().size)
        }
    }

    @Test fun `idempotent retry preserves original approval timestamp`() = runTest {
        insertSession()
        val first = repository.saveVerified(SessionId("session-1"), draft()) as DiarySaveResult.Verified
        nowEpochMs = 3_000

        val retry = repository.saveVerified(SessionId("session-1"), draft(topics = "Conectores editados")) as DiarySaveResult.Verified

        assertEquals(first.entry.approvedAtEpochMs, retry.entry.approvedAtEpochMs)
        assertEquals("Conectores editados", retry.entry.topics)
    }

    @Test fun `editing and deleting an archived entry changes the permanent record`() = runTest {
        insertSession()
        val saved = repository.saveVerified(SessionId("session-1"), draft()) as DiarySaveResult.Verified

        repository.update(saved.entry.copy(homework = "Ejercicio 8"))
        assertEquals("Ejercicio 8", repository.getBySession(SessionId("session-1"))?.homework)
        repository.markTemporariesDeleted(SessionId("session-1"), saved.entry.id)
        database.sessions().updateSessionStateUnchecked("session-1", SessionState.ARCHIVED.name, 3_000)
        repository.delete(saved.entry.id)

        assertEquals(null, repository.getBySession(SessionId("session-1")))
    }

    @Test fun `pending diary cannot be deleted and remains recoverable`() = runTest {
        insertSession()
        val saved = repository.saveVerified(SessionId("session-1"), draft()) as DiarySaveResult.Verified
        try {
            repository.delete(saved.entry.id)
            fail("Pending cleanup must block permanent diary deletion")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("limpieza"))
        }
        assertEquals(saved.entry, repository.getBySession(saved.entry.sessionId))
    }

    @Test fun `cleanup flag preserves newer archive edits and stale editor preserves cleanup flag`() = runTest {
        insertSession()
        val original = (repository.saveVerified(SessionId("session-1"), draft()) as DiarySaveResult.Verified).entry
        repository.update(original.copy(topics = "Edición durante limpieza"))
        assertTrue(repository.markTemporariesDeleted(original.sessionId, original.id))
        assertEquals("Edición durante limpieza", repository.getBySession(original.sessionId)!!.topics)
        repository.update(original.copy(homework = "Edición posterior", approvedAtEpochMs = 99, temporariesDeleted = false))
        val edited = repository.getBySession(original.sessionId)!!
        assertTrue(edited.temporariesDeleted)
        assertEquals(original.approvedAtEpochMs, edited.approvedAtEpochMs)
        assertEquals("Edición posterior", edited.homework)
    }

    @Test fun `stale edit and late cleanup cannot resurrect deleted diary`() = runTest {
        insertSession()
        val original = (repository.saveVerified(SessionId("session-1"), draft()) as DiarySaveResult.Verified).entry
        repository.markTemporariesDeleted(original.sessionId, original.id)
        database.sessions().updateSessionStateUnchecked("session-1", SessionState.ARCHIVED.name, 3_000)
        repository.delete(original.id)
        assertFalse(repository.markTemporariesDeleted(original.sessionId, original.id))
        try {
            repository.update(original.copy(topics = "No resucitar"))
            fail("Stale edit must fail")
        } catch (_: IllegalStateException) { }
        assertEquals(null, repository.getBySession(original.sessionId))
    }

    @Test fun `missing interpretation setting reads as conservative`() = runTest {
        assertEquals(InterpretationMode.CONSERVATIVE, repository.observeMode().first())
    }

    @Test fun `save verification propagates cancellation`() = runTest {
        insertSession()
        val cancelledRepository = RoomDiaryRepository(
            database,
            Clock { nowEpochMs },
            IdProvider { throw CancellationException("cancelled") },
        )

        try {
            cancelledRepository.saveVerified(SessionId("session-1"), draft())
            fail("Expected cancellation to propagate")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
    }

    private suspend fun insertSession() {
        database.sessions().insertSession(
            SessionEntity("session-1", "2026-09-12", CerLevel.B2.name, SessionState.AWAITING_REVIEW.name, 500, 600),
        )
    }

    private fun draft(topics: String = "Conectores") = DiaryDraftEntity(
        id = "draft-1",
        sessionId = "session-1",
        mode = InterpretationMode.CONSERVATIVE.name,
        topics = topics,
        activities = "Debate guiado",
        pages = "42-43",
        exercises = "3 y 4",
        homework = "Escribir un texto",
        updatedAtEpochMs = 1_000,
    )
}

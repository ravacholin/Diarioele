package com.capo.diarioclase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.AudioSegmentEntity
import com.capo.diarioclase.data.db.BlockCloseReason
import com.capo.diarioclase.data.db.BlockEntity
import com.capo.diarioclase.data.db.CerLevel
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.SegmentId
import com.capo.diarioclase.data.db.SegmentState
import com.capo.diarioclase.data.db.SessionEntity
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.SessionState
import com.capo.diarioclase.data.db.TranscriptionCheckpointEntity
import com.capo.diarioclase.data.db.TranscriptionRunEntity
import com.capo.diarioclase.data.repository.IdProvider
import com.capo.diarioclase.data.repository.RoomDiaryRepository
import com.capo.diarioclase.data.repository.RoomSessionRepository
import com.capo.diarioclase.data.repository.SessionRepository
import com.capo.diarioclase.diary.DiaryClipboardFormatter
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.diary.cleanup.CleanupCoordinator
import com.capo.diarioclase.diary.cleanup.CleanupOutcome
import com.capo.diarioclase.diary.cleanup.RoomTemporaryCleanupStore
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.recording.audio.CleanupFileStore
import com.capo.diarioclase.recording.audio.DeleteResult
import com.capo.diarioclase.recording.audio.FileSegmentStore
import com.capo.diarioclase.ui.capture.CaptureActions
import com.capo.diarioclase.ui.capture.CaptureStatus
import com.capo.diarioclase.ui.capture.CaptureViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FullJourneyTest {
    private lateinit var context: Context
    private lateinit var database: DiarioDatabase
    private lateinit var audioRoot: File
    private lateinit var files: FileSegmentStore
    private lateinit var archive: RoomDiaryRepository
    private lateinit var cleanup: CleanupCoordinator

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DiarioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        audioRoot = Files.createTempDirectory("diario-full-journey-").toFile()
        files = FileSegmentStore(audioRoot)
        archive = RoomDiaryRepository(database, Clock { 1_000 }, TestIds())
        cleanup = CleanupCoordinator(archive, files, RoomTemporaryCleanupStore(database.sessions()), Clock { 1_000 })
    }

    @After fun tearDown() {
        database.close()
        audioRoot.deleteRecursively()
    }

    @Test fun `review approval cleanup archive edit search and copy`() = runTest {
        val sessionId = SessionId("session")
        val blockId = "block"
        database.sessions().insertSession(SessionEntity(sessionId.value, "2026-09-12", CerLevel.B1.name, SessionState.AWAITING_REVIEW.name, 1, 1))
        database.sessions().insertBlock(BlockEntity(blockId, sessionId.value, 0, 1, 2, BlockCloseReason.FINALIZED.name))
        val openAudio = files.open(com.capo.diarioclase.data.db.BlockId(blockId), 0)
        files.append(openAudio, shortArrayOf(1, -1, 2, -2), 4)
        val readyAudio = files.close(openAudio)
        assertTrue(File(readyAudio.path).name.endsWith(".ready.wav"))
        database.sessions().saveSegment(AudioSegmentEntity(readyAudio.id.value, blockId, 0, readyAudio.path, File(readyAudio.path).length(), readyAudio.durationMs, readyAudio.sha256, SegmentState.READY.name))
        val draft = DiaryDraftEntity("draft", sessionId.value, InterpretationMode.CONSERVATIVE.name, "Pasado", "Lectura", "12", "3", "Escribir", 1)
        database.sessions().saveDraft(draft)
        database.sessions().saveTranscriptionRun(TranscriptionRunEntity(sessionId.value, "COMPLETED", false, readyAudio.durationMs, readyAudio.durationMs, null, null, 1))
        database.sessions().saveCheckpoint(TranscriptionCheckpointEntity(readyAudio.id.value, sessionId.value, readyAudio.durationMs, readyAudio.durationMs, 1, 1, "COMPLETED", null, 1))
        assertNotNull(database.sessions().transcriptionRun(sessionId.value))

        val outcome = cleanup.approveAndClean(sessionId, draft)

        assertTrue(outcome is CleanupOutcome.Archived)
        val archived = archive.getBySession(sessionId) ?: error("missing permanent diary")
        assertEquals(
            DiaryEntry("diary", sessionId, "2026-09-12", CerLevel.B1, "Pasado", "Lectura", "12", "3", "Escribir", 1_000, 1_000, true),
            archived,
        )
        assertFalse(File(readyAudio.path).exists())
        assertFalse(files.exists(readyAudio.id))
        assertNull(database.sessions().transcriptionRun(sessionId.value))
        assertTrue(database.sessions().checkpoints(sessionId.value).isEmpty())
        assertEquals(0, database.sessions().temporaryRowCount(sessionId.value))

        archive.update(archived.copy(topics = "Pasados y narración"))
        val edited = archive.getBySession(sessionId) ?: error("missing edited diary")
        assertEquals("Pasados y narración", edited.topics)
        assertEquals(1, archive.observeEntries("narracion").first().size)
        val copied = DiaryClipboardFormatter().format(edited)
        assertTrue(copied.contains("Pasados y narración"))
        assertFalse(copied.contains("temporariesDeleted", ignoreCase = true))
    }

    @Test fun `cleanup failure preserves whisper run and checkpoints`() = runTest {
        val sessionId = SessionId("whisper-session")
        val blockId = "whisper-block"
        database.sessions().insertSession(SessionEntity(sessionId.value, "2026-09-13", CerLevel.B1.name, SessionState.AWAITING_REVIEW.name, 1, 1))
        database.sessions().insertBlock(BlockEntity(blockId, sessionId.value, 0, 1, 2, BlockCloseReason.FINALIZED.name))
        val openAudio = files.open(com.capo.diarioclase.data.db.BlockId(blockId), 0)
        files.append(openAudio, shortArrayOf(1, -1, 2, -2), 4)
        val readyAudio = files.close(openAudio)
        database.sessions().saveSegment(AudioSegmentEntity(readyAudio.id.value, blockId, 0, readyAudio.path, File(readyAudio.path).length(), readyAudio.durationMs, readyAudio.sha256, SegmentState.READY.name))
        val draft = DiaryDraftEntity("whisper-draft", sessionId.value, InterpretationMode.CONSERVATIVE.name, "Pasado", "Lectura", "12", "3", "Escribir", 1)
        database.sessions().saveDraft(draft)
        database.sessions().saveTranscriptionRun(TranscriptionRunEntity(sessionId.value, "COMPLETED", false, readyAudio.durationMs, readyAudio.durationMs, null, null, 1))
        database.sessions().saveCheckpoint(TranscriptionCheckpointEntity(readyAudio.id.value, sessionId.value, readyAudio.durationMs, readyAudio.durationMs, 1, 1, "COMPLETED", null, 1))

        val failingFiles = object : CleanupFileStore {
            override suspend fun delete(segmentId: SegmentId) = DeleteResult.Failed("bloqueado")
            override suspend fun exists(segmentId: SegmentId) = true
        }
        val guardedCleanup = CleanupCoordinator(archive, failingFiles, RoomTemporaryCleanupStore(database.sessions()), Clock { 1_000 })

        val outcome = guardedCleanup.approveAndClean(sessionId, draft)

        assertTrue(outcome is CleanupOutcome.Pending)
        assertNotNull(database.sessions().transcriptionRun(sessionId.value))
        assertEquals(1, database.sessions().checkpoints(sessionId.value).size)
        assertTrue(File(readyAudio.path).exists())
        assertTrue(database.sessions().temporaryRowCount(sessionId.value) > 0)
    }

    @Test fun `file backed reopen surfaces pending cleanup without deleting temporaries`() = verifyCleanupRecovery(SessionState.CLEANUP_PENDING)

    @Test fun `file backed reopen surfaces approved boundary without deleting temporaries`() = verifyCleanupRecovery(SessionState.APPROVED)

    private fun verifyCleanupRecovery(recoveredState: SessionState) = runTest {
        val databaseName = "phase3-pending-${UUID.randomUUID()}.db"
        val sessionId = SessionId("pending-session")
        val audioRoot = Files.createTempDirectory("diario-pending-reopen-").toFile()
        val files = FileSegmentStore(audioRoot)
        val first = database(databaseName)
        var firstClosed = false
        try {
            first.sessions().insertSession(SessionEntity(sessionId.value, "2026-09-12", CerLevel.A2.name, recoveredState.name, 1, 1))
            first.sessions().insertBlock(BlockEntity("pending-block", sessionId.value, 0, 1, 2, BlockCloseReason.FINALIZED.name))
            val openAudio = files.open(com.capo.diarioclase.data.db.BlockId("pending-block"), 0)
            files.append(openAudio, shortArrayOf(1, -1), 2)
            val readyAudio = files.close(openAudio)
            assertTrue(File(readyAudio.path).name.endsWith(".ready.wav"))
            first.sessions().saveSegment(AudioSegmentEntity(readyAudio.id.value, "pending-block", 0, readyAudio.path, File(readyAudio.path).length(), readyAudio.durationMs, readyAudio.sha256, SegmentState.READY.name))
            val draft = DiaryDraftEntity("pending-draft", sessionId.value, InterpretationMode.CONSERVATIVE.name, "Narración", "Lectura", "12", "3", "Tarea", 1)
            first.sessions().saveDraft(draft)
            val firstArchive = RoomDiaryRepository(first, Clock { 1_000 }, TestIds())
            assertNotNull(firstArchive.saveVerified(sessionId, draft))
            first.close()
            firstClosed = true

            val reopened = database(databaseName)
            try {
                val repository = RoomSessionRepository(reopened, Clock { 1_000 })
                val reopenedArchive = RoomDiaryRepository(reopened, Clock { 1_000 }, TestIds())
                assertEquals(sessionId.value, reopened.sessions().latestCleanupPendingSession()?.id)
                val pending = repository.observeLatestCleanupPendingRecording().first() ?: error("missing pending recording")
                val permanent = reopenedArchive.getBySession(sessionId) ?: error("missing permanent diary")
                val actions = NoopCaptureActions()
                // Persistence is verified through the reopened Room repositories above. Feed those
                // recovered values directly to the UI boundary so this assertion does not race
                // Room's real executor or keep a database collector alive after close().
                val capture = CaptureViewModel(
                    RecoverySessionRepository(pending),
                    actions,
                    backgroundScope,
                    diaries = flowOf(listOf(permanent)),
                )
                runCurrent()

                assertEquals(recoveredState, pending.state)
                assertEquals(sessionId, pending.sessionId)
                assertEquals(DiaryEntry("diary", sessionId, "2026-09-12", CerLevel.A2, "Narración", "Lectura", "12", "3", "Tarea", 1_000, 1_000, false), permanent)
                assertEquals(CaptureStatus.CLEANUP_PENDING, capture.state.value.status)
                assertEquals("diary", capture.state.value.diaryId)
                assertEquals("Narración", capture.state.value.draft?.topics)
                assertEquals(0, actions.retryCalls)
                assertTrue(File(readyAudio.path).exists())
                assertTrue(files.exists(readyAudio.id))
                assertEquals(2, reopened.sessions().temporaryRowCount(sessionId.value))
            } finally {
                reopened.close()
            }
        } finally {
            if (!firstClosed) first.close()
            context.deleteDatabase(databaseName)
            audioRoot.deleteRecursively()
        }
    }

    private fun database(name: String) = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
        .addMigrations(DiarioDatabase.MIGRATION_1_2, DiarioDatabase.MIGRATION_2_3)
        .allowMainThreadQueries()
        .build()

    private class TestIds : IdProvider {
        override fun next() = "diary"
    }

    private class NoopCaptureActions : CaptureActions {
        var retryCalls = 0
        override suspend fun startNewDay() = Unit
        override suspend fun resume(id: SessionId) = Unit
        override suspend fun pause() = Unit
        override suspend fun markHomework(sessionId: SessionId, blockId: com.capo.diarioclase.data.db.BlockId) = Unit
        override suspend fun finalizeDay(sessionId: SessionId, state: SessionState) = Unit
        override suspend fun retryCleanup(sessionId: SessionId): CleanupOutcome {
            retryCalls += 1
            error("retry must be user initiated")
        }
    }

    private class RecoverySessionRepository(
        private val pending: com.capo.diarioclase.data.db.RecordingReport,
    ) : SessionRepository {
        override fun observeActiveSession(): Flow<com.capo.diarioclase.data.db.SessionAggregate?> = flowOf(null)
        override fun observeLatestFinalizedRecording(): Flow<com.capo.diarioclase.data.db.RecordingReport?> = flowOf(null)
        override fun observeLatestCleanupPendingRecording(): Flow<com.capo.diarioclase.data.db.RecordingReport?> = flowOf(pending)
        override suspend fun createSession(level: CerLevel?) = error("not used")
        override suspend fun startBlock(sessionId: SessionId) = error("not used")
        override suspend fun closeBlock(blockId: com.capo.diarioclase.data.db.BlockId, reason: BlockCloseReason) = error("not used")
        override suspend fun finalizeSession(sessionId: SessionId) = error("not used")
        override suspend fun updateSessionState(sessionId: SessionId, state: SessionState) = error("not used")
    }
}

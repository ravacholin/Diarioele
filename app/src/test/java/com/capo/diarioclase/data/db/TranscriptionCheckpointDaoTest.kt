package com.capo.diarioclase.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptionCheckpointDaoTest {
    private lateinit var database: DiarioDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DiarioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.sessions()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `saving the same segment checkpoint advances it without duplicating rows`() = runTest {
        insertParents()
        dao.saveTranscriptionRun(run())
        dao.saveCheckpoint(checkpoint(confirmedUntilMs = 28_000, processedWindows = 1))
        dao.saveCheckpoint(checkpoint(confirmedUntilMs = 58_000, processedWindows = 2))

        val checkpoints = dao.checkpoints("session")
        assertEquals(1, checkpoints.size)
        assertEquals(58_000, checkpoints.single().confirmedUntilMs)
        assertEquals(2, checkpoints.single().processedWindows)
    }

    @Test
    fun `pause request is durable and observable`() = runTest {
        insertParents()
        dao.saveTranscriptionRun(run())
        assertFalse(dao.transcriptionRun("session")!!.pauseRequested)

        assertEquals(1, dao.requestTranscriptionPause("session"))

        assertTrue(dao.transcriptionRun("session")!!.pauseRequested)
        assertTrue(dao.observeTranscriptionRun("session").first()!!.pauseRequested)
    }

    @Test
    fun `deleting an audio segment cascades its checkpoint`() = runTest {
        insertParents()
        dao.saveCheckpoint(checkpoint(confirmedUntilMs = 28_000, processedWindows = 1))

        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM audio_segments WHERE id='segment'",
        )

        assertTrue(dao.checkpoints("session").isEmpty())
    }

    private suspend fun insertParents() {
        dao.insertSession(
            SessionEntity(
                id = "session",
                pedagogicalDate = "2026-09-14",
                level = "C1",
                state = "FINALIZED",
                startedAtEpochMs = 1,
                updatedAtEpochMs = 1,
            ),
        )
        dao.insertBlock(
            BlockEntity(
                id = "block",
                sessionId = "session",
                ordinal = 0,
                startedAtEpochMs = 1,
                endedAtEpochMs = 2,
                closeReason = "PAUSED",
            ),
        )
        dao.saveSegment(
            AudioSegmentEntity(
                id = "segment",
                blockId = "block",
                ordinal = 0,
                path = "/private/segment.ready.wav",
                byteCount = 960_044,
                durationMs = 30_000,
                sha256 = "sha",
                state = "READY",
            ),
        )
    }

    private fun run() = TranscriptionRunEntity(
        sessionId = "session",
        state = "PROCESSING",
        pauseRequested = false,
        processedMs = 0,
        totalMs = 30_000,
        currentSegmentId = "segment",
        failure = null,
        updatedAtEpochMs = 1,
    )

    private fun checkpoint(
        confirmedUntilMs: Long,
        processedWindows: Int,
    ) = TranscriptionCheckpointEntity(
        audioSegmentId = "segment",
        sessionId = "session",
        confirmedUntilMs = confirmedUntilMs,
        totalMs = 60_000,
        processedWindows = processedWindows,
        totalWindows = 3,
        state = "PROCESSING",
        failure = null,
        updatedAtEpochMs = processedWindows.toLong(),
    )
}

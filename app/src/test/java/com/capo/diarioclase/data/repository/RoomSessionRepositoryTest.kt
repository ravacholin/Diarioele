package com.capo.diarioclase.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.BlockCloseReason
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.SessionState
import com.capo.diarioclase.data.db.AudioSegmentEntity
import com.capo.diarioclase.data.db.SegmentState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSessionRepositoryTest {
    private lateinit var database: DiarioDatabase
    private lateinit var clock: MutableClock
    private lateinit var repository: RoomSessionRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DiarioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = MutableClock(1_000)
        repository = RoomSessionRepository(database, clock, { "2026-09-11" }, SequentialIds())
    }

    @After fun tearDown() = database.close()

    @Test fun `pause and resume create separate durable blocks`() = runTest {
        val session = repository.createSession(null)
        val first = repository.startBlock(session)
        clock.value = 91_000
        repository.closeBlock(first, BlockCloseReason.PAUSED)
        val second = repository.startBlock(session)

        val aggregate = repository.observeActiveSession().first()
        assertEquals(SessionState.RECORDING, aggregate?.state)
        assertEquals(listOf(first, second), aggregate?.blocks)
        assertEquals(2, database.sessions().blockCount(session.value))
        assertNotNull(database.sessions().openBlock(session.value))
    }

    @Test fun `finalize closes open block and removes active session`() = runTest {
        val session = repository.createSession(null)
        repository.startBlock(session)
        clock.value = 61_000
        repository.finalizeSession(session)

        assertNull(database.sessions().activeSession())
        assertNull(database.sessions().openBlock(session.value))
        assertEquals(SessionState.FINALIZED.name, database.sessions().session(session.value)?.state)
    }

    @Test fun `illegal transition is rejected`() = runTest {
        val session = repository.createSession(null)
        val failure = runCatching { repository.updateSessionState(session, SessionState.APPROVED) }
        assertNotNull(failure.exceptionOrNull())
        assertEquals(SessionState.PAUSED.name, database.sessions().session(session.value)?.state)
    }

    @Test fun `finalized day exposes blocks and playable segments`() = runTest {
        val session = repository.createSession(null)
        val block = repository.startBlock(session)
        database.sessions().saveSegment(AudioSegmentEntity("segment-1", block.value, 0, "/private/segment.ready.wav", 32_044, 1_000, "hash", SegmentState.READY.name))
        clock.value = 2_000
        repository.finalizeSession(session)

        val report = repository.observeLatestFinalizedRecording().first { it != null }
        assertEquals(session, report?.sessionId)
        assertEquals(1, report?.blocks?.size)
        assertEquals("/private/segment.ready.wav", report?.blocks?.single()?.segments?.single()?.path)
        assertEquals(SegmentState.READY, report?.blocks?.single()?.segments?.single()?.state)
    }
}

private class MutableClock(var value: Long) : Clock {
    override fun nowEpochMs() = value
}

private class SequentialIds : IdProvider {
    private var next = 0
    override fun next() = "id-${next++}"
}

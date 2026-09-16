package com.capo.diarioclase.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.data.db.AudioSegmentEntity
import com.capo.diarioclase.data.db.BlockEntity
import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.SegmentId
import com.capo.diarioclase.data.db.SegmentState
import com.capo.diarioclase.data.db.SessionEntity
import com.capo.diarioclase.recording.audio.ReadySegment
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RoomSegmentMetadataStoreTest {
    private lateinit var database: DiarioDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DiarioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `ready-file reconciliation does not resurrect terminal segment states`() = runTest {
        val dao = database.sessions()
        dao.insertSession(SessionEntity("session", "2026-09-16", null, "PAUSED", 0, 0))
        dao.insertBlock(BlockEntity("block", "session", 0, 0, 1, "PAUSED"))
        val file = File.createTempFile("segment", ".ready.ogg").apply { writeBytes(byteArrayOf(1)) }
        val store = RoomSegmentMetadataStore(dao)

        try {
            for (state in listOf(SegmentState.TRANSCRIBED, SegmentState.FAILED, SegmentState.DELETED)) {
                dao.saveSegment(
                    AudioSegmentEntity(
                        id = "segment",
                        blockId = "block",
                        ordinal = 3,
                        path = file.absolutePath,
                        byteCount = 1,
                        durationMs = 1_000,
                        sha256 = "old",
                        state = state.name,
                        transcriptionAttempts = 2,
                        lastTranscriptionFailure = "kept",
                    ),
                )

                store.saveReady(
                    ReadySegment(SegmentId("segment"), BlockId("block"), file.absolutePath, 2_000, "new"),
                )

                val saved = requireNotNull(dao.segment("segment"))
                assertEquals(state.name, saved.state)
                assertEquals(2, saved.transcriptionAttempts)
                assertEquals("kept", saved.lastTranscriptionFailure)
            }
        } finally {
            file.delete()
        }
    }
}

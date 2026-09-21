package com.capo.diarioclase.processing.editorial

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.EditorialReportEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomEditorialReportStoreTest {
    @Test
    fun `late ready result cannot replace stale input hash`() = runTest {
        withStore { store ->
            store.begin("s", "new-hash")
            store.markStale("s")

            assertFalse(store.saveReadyIfCurrent("s", "new-hash", ready("s", "new-hash")))
            assertEquals(EditorialReportState.STALE.name, store.observe("s").first()!!.state)
        }
    }

    @Test
    fun `ready cache is returned only for matching hash`() = runTest {
        withStore { store ->
            store.begin("s", "hash-a")
            assertTrue(store.saveReadyIfCurrent("s", "hash-a", ready("s", "hash-a")))

            assertEquals("Resumen", store.readyFor("s", "hash-a")!!.summary)
            assertNull(store.readyFor("s", "hash-b"))
        }
    }

    @Test
    fun `failure cannot overwrite a newer generation`() = runTest {
        withStore { store ->
            store.begin("s", "old")
            store.begin("s", "new")

            store.markFailedIfCurrent("s", "old", "late failure")

            val current = store.observe("s").first()!!
            assertEquals("new", current.inputHash)
            assertEquals(EditorialReportState.GENERATING.name, current.state)
        }
    }

    private suspend fun withStore(block: suspend (RoomEditorialReportStore) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DiarioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            block(RoomEditorialReportStore(database, Clock { 10 }))
        } finally {
            database.close()
        }
    }

    private fun ready(sessionId: String, hash: String) = EditorialReportEntity(
        sessionId = sessionId,
        inputHash = hash,
        state = EditorialReportState.READY.name,
        rawJson = "{}",
        summary = "Resumen",
        materialText = "Página 42.",
        homeworkText = "",
        provider = "GEMINI",
        modelId = "gemini-2.5-flash",
        promptVersion = "p1",
        schemaVersion = "s1",
        validatorVersion = "v1",
        failure = null,
        updatedAtEpochMs = 9,
    )
}

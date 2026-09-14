package com.capo.diarioclase.processing.work

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.TranscriptionRunEntity
import com.capo.diarioclase.processing.evidence.InterpretationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionWorkSchedulerTest {
    @Test
    fun `resume replaces only the same session work`() = runTest {
        val commands = FakeCommands()
        val work = FakeWorkEnqueuer()
        val scheduler = TranscriptionWorkScheduler(commands, work)
        val sessionId = SessionId("day")

        scheduler.start(sessionId, InterpretationMode.CONSERVATIVE)
        scheduler.resume(sessionId, InterpretationMode.CONSERVATIVE)

        assertEquals("transcription-day", work.lastUniqueName)
        assertEquals(ExistingWorkPolicy.REPLACE, work.lastPolicy)
        assertEquals(2, commands.clearCalls)
        assertTrue(work.lastRequest!!.tags.contains("transcription-day"))
        assertEquals(
            InterpretationMode.CONSERVATIVE.name,
            work.lastRequest!!.workSpec.input.getString(TranscriptionWorker.KEY_MODE),
        )
    }

    @Test
    fun `pause is persisted before unique work is cancelled`() = runTest {
        val events = mutableListOf<String>()
        val commands = FakeCommands(events)
        val work = FakeWorkEnqueuer(events)
        val scheduler = TranscriptionWorkScheduler(commands, work)

        scheduler.pause(SessionId("day"))

        assertEquals(listOf("persist-pause", "cancel"), events)
        assertEquals("transcription-day", work.cancelledName)
    }

    private class FakeCommands(
        private val events: MutableList<String> = mutableListOf(),
    ) : TranscriptionRunCommands {
        var clearCalls = 0
        private val flow = MutableStateFlow<TranscriptionRunEntity?>(null)

        override suspend fun requestPause(sessionId: String): Int {
            events += "persist-pause"
            return 1
        }

        override suspend fun clearPause(sessionId: String): Int {
            clearCalls += 1
            return 1
        }

        override fun observeRun(sessionId: String): Flow<TranscriptionRunEntity?> = flow
    }

    private class FakeWorkEnqueuer(
        private val events: MutableList<String> = mutableListOf(),
    ) : UniqueWorkEnqueuer {
        var lastUniqueName: String? = null
        var lastPolicy: ExistingWorkPolicy? = null
        var lastRequest: OneTimeWorkRequest? = null
        var cancelledName: String? = null

        override fun enqueueUnique(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            lastUniqueName = name
            lastPolicy = policy
            lastRequest = request
        }

        override fun cancelUnique(name: String) {
            events += "cancel"
            cancelledName = name
        }
    }
}

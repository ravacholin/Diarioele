package com.capo.diarioclase.processing.work

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.capo.diarioclase.data.db.SessionDao
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.TranscriptionRunEntity
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface TranscriptionScheduler {
    suspend fun start(sessionId: SessionId, mode: InterpretationMode)
    suspend fun pause(sessionId: SessionId)
    suspend fun resume(sessionId: SessionId, mode: InterpretationMode)
    fun observeProgress(sessionId: SessionId): Flow<TranscriptionProgress?>
}

interface TranscriptionRunCommands {
    suspend fun requestPause(sessionId: String): Int
    suspend fun clearPause(sessionId: String): Int
    fun observeRun(sessionId: String): Flow<TranscriptionRunEntity?>
}

class DaoTranscriptionRunCommands(
    private val dao: SessionDao,
) : TranscriptionRunCommands {
    override suspend fun requestPause(sessionId: String) =
        dao.requestTranscriptionPause(sessionId)

    override suspend fun clearPause(sessionId: String) =
        dao.clearTranscriptionPause(sessionId)

    override fun observeRun(sessionId: String) =
        dao.observeTranscriptionRun(sessionId)
}

interface UniqueWorkEnqueuer {
    fun enqueueUnique(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )
    fun cancelUnique(name: String)
}

class WorkManagerEnqueuer(
    private val workManager: WorkManager,
) : UniqueWorkEnqueuer {
    override fun enqueueUnique(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override fun cancelUnique(name: String) {
        workManager.cancelUniqueWork(name)
    }
}

class TranscriptionWorkScheduler(
    private val commands: TranscriptionRunCommands,
    private val work: UniqueWorkEnqueuer,
) : TranscriptionScheduler {
    override suspend fun start(
        sessionId: SessionId,
        mode: InterpretationMode,
    ) = enqueue(sessionId, mode)

    override suspend fun resume(
        sessionId: SessionId,
        mode: InterpretationMode,
    ) = enqueue(sessionId, mode)

    override suspend fun pause(sessionId: SessionId) {
        commands.requestPause(sessionId.value)
        work.cancelUnique(uniqueName(sessionId))
    }

    override fun observeProgress(sessionId: SessionId): Flow<TranscriptionProgress?> =
        commands.observeRun(sessionId.value).map { run ->
            run?.let {
                TranscriptionProgress(
                    sessionId = sessionId,
                    processedMs = it.processedMs,
                    totalMs = it.totalMs,
                    currentBlock = 0,
                    currentWindow = 0,
                    totalWindows = 0,
                    state = TranscriptionRunState.valueOf(it.state),
                    failure = it.failure?.let { code ->
                        runCatching { TranscriptionFailure.valueOf(code) }.getOrNull()
                    },
                )
            }
        }

    private suspend fun enqueue(
        sessionId: SessionId,
        mode: InterpretationMode,
    ) {
        commands.clearPause(sessionId.value)
        work.enqueueUnique(
            uniqueName(sessionId),
            ExistingWorkPolicy.REPLACE,
            TranscriptionWorker.request(sessionId, mode),
        )
    }

    companion object {
        fun uniqueName(sessionId: SessionId) =
            "transcription-" + sessionId.value
    }
}

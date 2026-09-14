package com.capo.diarioclase.processing.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.capo.diarioclase.DiarioClaseApp
import com.capo.diarioclase.R
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class TranscriptionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val app get() = applicationContext as DiarioClaseApp

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)?.let(::SessionId)
            ?: return Result.failure(errorData(TranscriptionFailure.INTERNAL))
        val mode = inputData.getString(KEY_MODE)
            ?.let { runCatching { InterpretationMode.valueOf(it) }.getOrNull() }
            ?: return Result.failure(errorData(TranscriptionFailure.INTERNAL))

        setForeground(createForegroundInfo(sessionId, null))
        return try {
            while (!isStopped) {
                when (val outcome = withTimeout(WINDOW_TIMEOUT_MS) {
                    app.transcriptionCoordinator.processNext(sessionId, mode)
                }) {
                    is ProcessingStepOutcome.WindowSaved -> {
                        setProgress(progressData(outcome.progress))
                        setForeground(createForegroundInfo(sessionId, outcome.progress))
                    }
                    is ProcessingStepOutcome.Complete -> return Result.success()
                    is ProcessingStepOutcome.Paused -> return Result.success()
                    is ProcessingStepOutcome.Failed ->
                        return Result.failure(
                            errorData(outcome.failure, outcome.retryable),
                        )
                }
            }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            app.transcriptionCoordinator.failCurrent(
                sessionId,
                TranscriptionFailure.TIMEOUT,
                retryable = true,
            )
            Result.failure(errorData(TranscriptionFailure.TIMEOUT, retryable = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.failure(errorData(TranscriptionFailure.INTERNAL, retryable = true))
        } finally {
            if (isStopped) {
                runCatching { app.whisperEngine.cancel() }
            }
        }
    }

    private fun createForegroundInfo(
        sessionId: SessionId,
        progress: TranscriptionProgress?,
    ): ForegroundInfo {
        createNotificationChannel()
        val percent = progress?.let {
            if (it.totalMs <= 0L) 0
            else ((it.processedMs * 100L) / it.totalMs).toInt().coerceIn(0, 100)
        } ?: 0
        val pauseIntent = Intent(applicationContext, TranscriptionCommandReceiver::class.java)
            .setAction(TranscriptionCommandReceiver.ACTION_PAUSE)
            .putExtra(KEY_SESSION_ID, sessionId.value)
        val pendingPause = PendingIntent.getBroadcast(
            applicationContext,
            sessionId.value.hashCode(),
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("TRANSCRIBIENDO EN ESPAÑOL")
            .setContentText(
                if (progress == null) "Preparando el motor local"
                else percent.toString() + "% confirmado",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, progress == null || progress.totalMs <= 0L)
            .addAction(0, "PAUSAR", pendingPause)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, serviceType)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Transcripción local",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_MODE = "interpretation_mode"
        const val KEY_FAILURE = "failure"
        const val KEY_RETRYABLE = "retryable"
        const val KEY_PROCESSED_MS = "processed_ms"
        const val KEY_TOTAL_MS = "total_ms"
        const val CHANNEL_ID = "diarioclase_transcription"
        const val NOTIFICATION_ID = 2_041
        const val WINDOW_TIMEOUT_MS = 300_000L

        fun request(
            sessionId: SessionId,
            mode: InterpretationMode,
        ): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(TranscriptionWorker::class.java)
                .setInputData(
                    workDataOf(
                        KEY_SESSION_ID to sessionId.value,
                        KEY_MODE to mode.name,
                    ),
                )
                .addTag(TranscriptionWorkScheduler.uniqueName(sessionId))
                .build()

        private fun progressData(progress: TranscriptionProgress): Data =
            workDataOf(
                KEY_PROCESSED_MS to progress.processedMs,
                KEY_TOTAL_MS to progress.totalMs,
            )

        private fun errorData(
            failure: TranscriptionFailure,
            retryable: Boolean = false,
        ): Data = workDataOf(
            KEY_FAILURE to failure.name,
            KEY_RETRYABLE to retryable,
        )
    }
}

class TranscriptionCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val sessionId = intent.getStringExtra(TranscriptionWorker.KEY_SESSION_ID) ?: return
        val pendingResult = goAsync()
        val app = context.applicationContext as DiarioClaseApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.transcriptionScheduler.pause(SessionId(sessionId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE =
            "com.capo.diarioclase.action.PAUSE_TRANSCRIPTION"
    }
}

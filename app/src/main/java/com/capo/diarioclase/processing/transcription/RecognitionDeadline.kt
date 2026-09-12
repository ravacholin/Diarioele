package com.capo.diarioclase.processing.transcription

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal fun recognitionDeadlineMs(durationMs: Long): Long =
    (durationMs.coerceAtLeast(0) + RESULT_GRACE_MS).coerceIn(MIN_DEADLINE_MS, MAX_DEADLINE_MS)

internal suspend fun <T> runRecognitionWithDeadline(durationMs: Long, block: suspend () -> T): T? =
    withTimeoutOrNull(recognitionDeadlineMs(durationMs)) { block() }

internal suspend fun awaitAudioSourceConsumption(
    writer: Job,
    copyCompleted: () -> Boolean,
    audioPipeDrained: () -> Boolean,
): Boolean =
    withTimeoutOrNull(AUDIO_CONSUMPTION_GRACE_MS) {
        writer.join()
        if (!runCatching { copyCompleted() }.getOrDefault(false)) return@withTimeoutOrNull false
        while (!runCatching { audioPipeDrained() }.getOrDefault(false)) delay(AUDIO_DRAIN_POLL_MS)
        true
    } ?: false

/** A recognizer failure is already actionable; only a success needs supplied-audio proof. */
internal suspend fun validateSuppliedAudioResult(
    result: TranscriptResult,
    writer: Job,
    copyCompleted: () -> Boolean,
    audioPipeDrained: () -> Boolean,
): TranscriptResult {
    if (result is TranscriptResult.Failure) return result
    return if (awaitAudioSourceConsumption(writer, copyCompleted, audioPipeDrained)) result else TranscriptResult.Failure(
        TranscriptionFailure.AUDIO_SOURCE_UNSUPPORTED,
        false,
        "El reconocedor del teléfono no consumió completamente el audio guardado",
    )
}

private const val RESULT_GRACE_MS = 15_000L
private const val MIN_DEADLINE_MS = 20_000L
private const val MAX_DEADLINE_MS = 210_000L
private const val AUDIO_CONSUMPTION_GRACE_MS = 1_000L
private const val AUDIO_DRAIN_POLL_MS = 10L

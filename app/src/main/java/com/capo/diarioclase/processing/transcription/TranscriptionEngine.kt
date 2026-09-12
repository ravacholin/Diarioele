package com.capo.diarioclase.processing.transcription

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.recording.audio.ReadySegment

data class TranscriptSpan(
    val id: String,
    val audioSegmentId: String,
    val blockId: BlockId,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Double,
)

enum class TranscriptionFailure { ON_DEVICE_UNAVAILABLE, LANGUAGE_UNAVAILABLE, AUDIO_SOURCE_UNSUPPORTED, INVALID_AUDIO, RECOGNIZER_BUSY, NO_SPEECH, TIMEOUT, UNKNOWN }

sealed interface TranscriptResult {
    data class Success(val spans: List<TranscriptSpan>) : TranscriptResult
    data class Failure(val code: TranscriptionFailure, val retryable: Boolean, val detail: String? = null) : TranscriptResult
}

fun interface TranscriptionEngine { suspend fun transcribe(segment: ReadySegment): TranscriptResult }

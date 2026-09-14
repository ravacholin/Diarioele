package com.capo.diarioclase.processing.transcription

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.SegmentId
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

data class AudioWindow(
    val segmentId: SegmentId,
    val blockId: BlockId,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val samples: FloatArray,
)

enum class TranscriptionFailure {
    ON_DEVICE_UNAVAILABLE,
    LANGUAGE_UNAVAILABLE,
    AUDIO_SOURCE_UNSUPPORTED,
    RECOGNIZER_BUSY,
    MODEL_MISSING,
    MODEL_INVALID,
    NATIVE_UNAVAILABLE,
    INVALID_AUDIO,
    OUT_OF_MEMORY,
    NO_SPEECH,
    TIMEOUT,
    INTERRUPTED,
    INTERNAL,
    UNKNOWN,
}

sealed interface TranscriptResult {
    data class Success(val spans: List<TranscriptSpan>) : TranscriptResult
    data class Failure(
        val code: TranscriptionFailure,
        val retryable: Boolean,
        val detail: String? = null,
    ) : TranscriptResult
}

sealed interface WindowTranscriptResult {
    data class Success(val spans: List<TranscriptSpan>) : WindowTranscriptResult
    data class Failure(
        val code: TranscriptionFailure,
        val retryable: Boolean,
        val detail: String? = null,
    ) : WindowTranscriptResult
}

fun interface TranscriptionEngine {
    suspend fun transcribe(segment: ReadySegment): TranscriptResult
}

fun interface WindowTranscriptionEngine {
    suspend fun transcribe(window: AudioWindow): WindowTranscriptResult
}

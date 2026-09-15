package com.capo.diarioclase.processing.transcription

import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WhisperTranscriptionEngine(
    private val modelProvider: WhisperModelProvider,
    private val nativeRuntime: WhisperNativeRuntime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val threadCount: Int = recommendedThreadCount(),
) : WindowTranscriptionEngine, AutoCloseable {
    private val loadMutex = Mutex()
    @Volatile private var loadedModelPath: String? = null

    override suspend fun transcribe(window: AudioWindow): WindowTranscriptResult =
        transcribe(window) {}

    override suspend fun transcribe(
        window: AudioWindow,
        onProgress: (Int) -> Unit,
    ): WindowTranscriptResult {
        if (window.samples.isEmpty() || window.endMs <= window.startMs) {
            return failure(TranscriptionFailure.INVALID_AUDIO, retryable = false)
        }

        return try {
            ensureLoaded()
            val nativeResult = withContext(dispatcher) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { nativeRuntime.cancel() }
                    try {
                        val result = nativeRuntime.transcribe(
                            samples = window.samples,
                            options = WhisperOptions(threads = threadCount),
                            onProgress = onProgress,
                        )
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
            WindowTranscriptResult.Success(
                nativeResult.spans.mapIndexedNotNull { index, span ->
                    val text = span.text.trim()
                    if (text.isEmpty()) return@mapIndexedNotNull null
                    TranscriptSpan(
                        id = listOf(
                            window.segmentId.value,
                            window.index,
                            span.startMs,
                            index,
                        ).joinToString(":"),
                        audioSegmentId = window.segmentId.value,
                        blockId = window.blockId,
                        startMs = window.startMs + span.startMs.coerceAtLeast(0L),
                        endMs = window.startMs + span.endMs.coerceAtLeast(span.startMs),
                        text = text,
                        confidence = span.confidence.coerceIn(0.0, 1.0),
                    )
                },
            )
        } catch (_: ModelMissingException) {
            failure(TranscriptionFailure.MODEL_MISSING, retryable = false)
        } catch (_: ModelInvalidException) {
            failure(TranscriptionFailure.MODEL_INVALID, retryable = false)
        } catch (_: FileNotFoundException) {
            failure(TranscriptionFailure.MODEL_MISSING, retryable = false)
        } catch (_: UnsatisfiedLinkError) {
            failure(TranscriptionFailure.NATIVE_UNAVAILABLE, retryable = false)
        } catch (_: OutOfMemoryError) {
            failure(TranscriptionFailure.OUT_OF_MEMORY, retryable = true)
        } catch (_: InterruptedException) {
            failure(TranscriptionFailure.INTERRUPTED, retryable = true)
        } catch (cancelled: CancellationException) {
            nativeRuntime.cancel()
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            failure(TranscriptionFailure.INVALID_AUDIO, retryable = false, detail = invalid.message)
        } catch (error: Throwable) {
            failure(TranscriptionFailure.INTERNAL, retryable = true, detail = error.message)
        }
    }

    private suspend fun ensureLoaded() {
        if (loadedModelPath != null) return
        loadMutex.withLock {
            if (loadedModelPath != null) return@withLock
            val model = modelProvider.ensureInstalled()
            nativeRuntime.load(model.absolutePath)
            loadedModelPath = model.absolutePath
        }
    }

    fun cancel() {
        nativeRuntime.cancel()
    }

    override fun close() {
        loadedModelPath = null
        nativeRuntime.close()
    }

    private fun failure(
        code: TranscriptionFailure,
        retryable: Boolean,
        detail: String? = null,
    ) = WindowTranscriptResult.Failure(code, retryable, detail)

    companion object {
        private fun recommendedThreadCount(): Int =
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}

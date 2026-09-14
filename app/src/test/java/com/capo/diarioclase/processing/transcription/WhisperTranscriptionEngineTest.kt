package com.capo.diarioclase.processing.transcription

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.SegmentId
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperTranscriptionEngineTest {
    @Test
    fun `engine always requests Spanish transcription`() = runTest {
        val native = FakeWhisperRuntime(NativeWindowResult(emptyList()))
        val engine = WhisperTranscriptionEngine(
            modelProvider = WhisperModelProvider { File("model.bin") },
            nativeRuntime = native,
            dispatcher = StandardTestDispatcher(testScheduler),
            threadCount = 2,
        )

        engine.transcribe(window())

        assertEquals("es", native.lastOptions?.language)
        assertFalse(native.lastOptions!!.translate)
        assertFalse(native.lastOptions!!.detectLanguage)
        assertTrue(native.lastOptions!!.prompt.contains("vos"))
        assertTrue(native.lastOptions!!.prompt.contains("páginas"))
    }

    @Test
    fun `native relative spans become absolute window spans`() = runTest {
        val native = FakeWhisperRuntime(
            NativeWindowResult(
                listOf(NativeSpan(500, 1_500, " página doce ", 1.2)),
            ),
        )
        val engine = WhisperTranscriptionEngine(
            modelProvider = WhisperModelProvider { File("model.bin") },
            nativeRuntime = native,
            dispatcher = StandardTestDispatcher(testScheduler),
            threadCount = 2,
        )

        val result = engine.transcribe(window(startMs = 28_000)) as WindowTranscriptResult.Success

        assertEquals(28_500, result.spans.single().startMs)
        assertEquals(29_500, result.spans.single().endMs)
        assertEquals("página doce", result.spans.single().text)
        assertEquals(1.0, result.spans.single().confidence, 0.0)
    }

    @Test
    fun `missing model has an explicit non retryable failure`() = runTest {
        val engine = WhisperTranscriptionEngine(
            modelProvider = WhisperModelProvider { throw ModelMissingException() },
            nativeRuntime = FakeWhisperRuntime(NativeWindowResult(emptyList())),
            dispatcher = StandardTestDispatcher(testScheduler),
            threadCount = 2,
        )

        val result = engine.transcribe(window()) as WindowTranscriptResult.Failure

        assertEquals(TranscriptionFailure.MODEL_MISSING, result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun `empty audio is rejected before native inference`() = runTest {
        val native = FakeWhisperRuntime(NativeWindowResult(emptyList()))
        val engine = WhisperTranscriptionEngine(
            modelProvider = WhisperModelProvider { File("model.bin") },
            nativeRuntime = native,
            dispatcher = StandardTestDispatcher(testScheduler),
            threadCount = 2,
        )

        val result = engine.transcribe(window(samples = floatArrayOf())) as WindowTranscriptResult.Failure

        assertEquals(TranscriptionFailure.INVALID_AUDIO, result.code)
        assertEquals(0, native.transcriptionCalls)
    }

    private fun window(
        startMs: Long = 0,
        samples: FloatArray = FloatArray(16_000),
    ) = AudioWindow(
        segmentId = SegmentId("segment"),
        blockId = BlockId("block"),
        index = 0,
        startMs = startMs,
        endMs = startMs + 1_000,
        samples = samples,
    )

    private class FakeWhisperRuntime(
        private val result: NativeWindowResult,
    ) : WhisperNativeRuntime {
        var lastOptions: WhisperOptions? = null
        var transcriptionCalls = 0

        override fun load(modelPath: String) = Unit

        override fun transcribe(
            samples: FloatArray,
            options: WhisperOptions,
        ): NativeWindowResult {
            transcriptionCalls += 1
            lastOptions = options
            return result
        }

        override fun cancel() = Unit
        override fun close() = Unit
    }
}

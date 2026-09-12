package com.capo.diarioclase.processing.transcription

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionDeadlineTest {
    @Test
    fun `ten second audio cannot wait longer than twenty five seconds`() = runTest {
        val result = runRecognitionWithDeadline(durationMs = 10_000) { awaitCancellation() }

        assertNull(result)
        assertEquals(25_000, testScheduler.currentTime)
    }

    @Test
    fun `deadline allows audio duration plus fifteen seconds`() {
        assertEquals(195_000, recognitionDeadlineMs(durationMs = 180_000))
    }

    @Test
    fun `deadline is capped for unexpectedly long segments`() {
        assertEquals(210_000, recognitionDeadlineMs(durationMs = 600_000))
    }

    @Test
    fun `recognition result is rejected when engine never consumes audio pipe`() = runTest {
        val stalledWriter = backgroundScope.launch { awaitCancellation() }

        assertFalse(awaitAudioSourceConsumption(stalledWriter, { false }, { false }))
        assertEquals(1_000, testScheduler.currentTime)
    }

    @Test
    fun `recognition result is accepted only after full pipe copy and drain`() = runTest {
        var copied = false
        val writer = launch { copied = true }
        runCurrent()

        assertTrue(awaitAudioSourceConsumption(writer, { copied }, { true }))
    }

    @Test fun `buffered short audio without a reader cannot validate a success`() = runTest {
        val writer = launch { }
        runCurrent()
        val result = validateSuppliedAudioResult(TranscriptResult.Success(emptyList()), writer, { true }, { false })
        assertEquals(TranscriptionFailure.AUDIO_SOURCE_UNSUPPORTED, (result as TranscriptResult.Failure).code)
    }

    @Test fun `missing language and busy errors survive an unconsumed pipe`() = runTest {
        val writer = backgroundScope.launch { awaitCancellation() }
        for (reason in listOf(TranscriptionFailure.LANGUAGE_UNAVAILABLE, TranscriptionFailure.RECOGNIZER_BUSY)) {
            val failure = TranscriptResult.Failure(reason, true, "actionable")
            assertEquals(failure, validateSuppliedAudioResult(failure, writer, { false }, { error("Failure must bypass drain check") }))
        }
        assertEquals(0, testScheduler.currentTime)
    }
}

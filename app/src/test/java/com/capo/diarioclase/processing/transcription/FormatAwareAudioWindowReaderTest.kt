package com.capo.diarioclase.processing.transcription

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatAwareAudioWindowReaderTest {
    private val plan = AudioWindowPlan(
        index = 0,
        startMs = 0,
        endMs = 1_000,
        confirmedUntilMs = 1_000,
    )

    @Test
    fun `routes legacy WAV and new OGG to their reader`() {
        val wav = RecordingReader(floatArrayOf(1f))
        val ogg = RecordingReader(floatArrayOf(2f))
        val reader = FormatAwareAudioWindowReader(wav = wav, ogg = ogg)

        assertArrayEquals(floatArrayOf(1f), reader.read(File("a.ready.wav"), plan), 0f)
        assertArrayEquals(floatArrayOf(2f), reader.read(File("a.ready.ogg"), plan), 0f)
        assertEquals(1, wav.calls)
        assertEquals(1, ogg.calls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a file without a supported container`() {
        FormatAwareAudioWindowReader(RecordingReader(), RecordingReader())
            .read(File("a.raw"), plan)
    }
}

private class RecordingReader(
    private val result: FloatArray = FloatArray(0),
) : AudioWindowReader {
    var calls: Int = 0
        private set

    override fun read(file: File, plan: AudioWindowPlan): FloatArray {
        calls += 1
        return result
    }
}

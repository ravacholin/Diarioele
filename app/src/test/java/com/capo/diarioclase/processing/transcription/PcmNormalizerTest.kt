package com.capo.diarioclase.processing.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class PcmNormalizerTest {
    @Test
    fun `downmixes interleaved stereo by averaging channels`() {
        assertArrayEquals(
            shortArrayOf(0, 10_000),
            PcmNormalizer.toMono(
                interleaved = shortArrayOf(10_000, -10_000, 20_000, 0),
                channelCount = 2,
            ),
        )
    }

    @Test
    fun `resamples 48 kHz to 16 kHz with exact duration`() {
        val input = sinePcm(sampleRate = 48_000, frequencyHz = 440.0, durationSeconds = 1)

        val output = PcmNormalizer.resampleMono(
            input = input,
            sourceSampleRate = 48_000,
            targetSampleRate = 16_000,
        )

        assertEquals(16_000, output.size)
        assertTrue(rms(output) > 5_000.0)
    }

    @Test
    fun `downsampling attenuates frequencies above target Nyquist limit`() {
        val aliasedInput = sinePcm(
            sampleRate = 48_000,
            frequencyHz = 12_000.0,
            durationSeconds = 1,
        )

        val output = PcmNormalizer.resampleMono(
            input = aliasedInput,
            sourceSampleRate = 48_000,
            targetSampleRate = 16_000,
        )

        assertTrue("El remuestreo debe filtrar 12 kHz antes de reducir a 16 kHz", rms(output) < 1_500.0)
    }

    @Test
    fun `converts PCM16 to Whisper float range`() {
        assertArrayEquals(
            floatArrayOf(-1f, 0f, Short.MAX_VALUE / 32_768f),
            PcmNormalizer.toFloat(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)),
            0.000001f,
        )
    }

    private fun sinePcm(
        sampleRate: Int,
        frequencyHz: Double,
        durationSeconds: Int,
    ): ShortArray = ShortArray(sampleRate * durationSeconds) { index ->
        (sin(2.0 * PI * frequencyHz * index / sampleRate) * 20_000.0).toInt().toShort()
    }

    private fun rms(samples: ShortArray): Double {
        val meanSquare = samples.fold(0.0) { total, sample ->
            total + sample.toDouble() * sample
        } / samples.size
        return sqrt(meanSquare)
    }
}

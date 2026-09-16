package com.capo.diarioclase.recording.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class AndroidOpusEncoderTest {
    @Test
    fun encodesStreamingPcmAsInspectableOggOpus() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val output = File(context.cacheDir, "opus-round-trip-${System.nanoTime()}.ogg")
        val config = AudioEncodingConfig()
        val encoder = AndroidOpusEncoder(output, config)

        try {
            val pcm = twoTonePcm(durationSeconds = 3, sampleRate = config.sampleRate)
            var offset = 0
            while (offset < pcm.size) {
                val count = minOf(960, pcm.size - offset)
                encoder.append(pcm.copyOfRange(offset, offset + count), count)
                offset += count
            }
            val encoded = encoder.finish()
            val inspected = OggInspector().inspect(output)

            assertEquals("audio/opus", encoded.mimeType)
            assertEquals(16_000, inspected.sampleRate)
            assertEquals(1, inspected.channelCount)
            assertTrue(inspected.durationMs in 2_900L..3_100L)

            val sampleTimes = sampleTimesUs(output)
            assertTrue(sampleTimes.isNotEmpty())
            assertTrue(sampleTimes.zipWithNext().all { (previous, next) -> next > previous })
        } finally {
            encoder.close()
            output.delete()
        }
    }

    private fun twoTonePcm(durationSeconds: Int, sampleRate: Int): ShortArray =
        ShortArray(durationSeconds * sampleRate) { index ->
            val time = index.toDouble() / sampleRate
            val sample = 0.35 * sin(2.0 * PI * 440.0 * time) +
                0.20 * sin(2.0 * PI * 880.0 * time)
            (sample * Short.MAX_VALUE).toInt().toShort()
        }

    private fun sampleTimesUs(file: File): List<Long> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) == true
            }
            extractor.selectTrack(trackIndex)
            buildList {
                while (extractor.sampleTime >= 0L) {
                    add(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
            }
        } finally {
            extractor.release()
        }
    }
}

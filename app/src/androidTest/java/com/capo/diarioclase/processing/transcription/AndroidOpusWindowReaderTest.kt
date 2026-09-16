package com.capo.diarioclase.processing.transcription

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.capo.diarioclase.recording.audio.AndroidOpusEncoder
import com.capo.diarioclase.recording.audio.AudioEncodingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AndroidOpusWindowReaderTest {
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    fun decodesOnlyRequestedWindowAsMono16kFloatPcm() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ogg = File(context.cacheDir, "opus-window-${System.nanoTime()}.ogg")
        val encoder = AndroidOpusEncoder(ogg, AudioEncodingConfig())

        try {
            val pcm = ShortArray(16_000 * 3) { index ->
                (sin(2.0 * PI * 440.0 * index / 16_000.0) * 16_000.0).toInt().toShort()
            }
            pcm.asList().chunked(960).forEach { chunk ->
                val samples = chunk.toShortArray()
                encoder.append(samples, samples.size)
            }
            encoder.finish()

            val samples = AndroidOpusWindowReader().read(
                ogg,
                AudioWindowPlan(
                    index = 0,
                    startMs = 500,
                    endMs = 1_500,
                    confirmedUntilMs = 1_500,
                ),
            )

            assertEquals(16_000, samples.size)
            assertTrue(samples.all { it.isFinite() && it in -1f..1f })
            assertTrue(samples.maxOf { abs(it) } > 0.1f)
        } finally {
            encoder.close()
            ogg.delete()
        }
    }
}

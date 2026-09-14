package com.capo.diarioclase.processing.transcription

import com.capo.diarioclase.recording.audio.WavHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class AudioWindowingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a 65 second segment creates three windows with two second overlap`() {
        assertEquals(
            listOf(
                AudioWindowPlan(0, 0, 30_000, 28_000),
                AudioWindowPlan(1, 28_000, 58_000, 56_000),
                AudioWindowPlan(2, 56_000, 65_000, 65_000),
            ),
            AudioWindowPlanner.plan(65_000),
        )
    }

    @Test
    fun `empty duration has no windows`() {
        assertTrue(AudioWindowPlanner.plan(0).isEmpty())
    }

    @Test
    fun `reader returns exactly the requested PCM range`() {
        val file = temporaryWav(ShortArray(32_000) { it.toShort() })

        val samples = PcmWindowReader.read(
            file,
            AudioWindowPlan(0, 1_000, 2_000, 2_000),
        )

        assertEquals(16_000, samples.size)
        assertEquals(16_000f / 32_768f, samples.first(), 0.0001f)
        assertEquals(31_999f / 32_768f, samples.last(), 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reader rejects a range beyond the declared audio`() {
        val file = temporaryWav(ShortArray(16_000))
        PcmWindowReader.read(file, AudioWindowPlan(0, 0, 2_000, 2_000))
    }

    private fun temporaryWav(samples: ShortArray): File {
        val file = temporaryFolder.newFile("segment.ready.wav")
        RandomAccessFile(file, "rw").use { output ->
            output.write(WavHeader.forPcm(samples.size * 2).encode())
            samples.forEach { sample ->
                val value = sample.toInt()
                output.write(value and 0xff)
                output.write((value ushr 8) and 0xff)
            }
        }
        return file
    }
}

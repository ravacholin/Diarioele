package com.capo.diarioclase.recording.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusPcmFramerTest {
    @Test
    fun `4096 sample microphone reads become exact 20 ms Opus frames`() {
        val framer = OpusPcmFramer(sampleRate = 16_000)
        val input = ShortArray(4_096 * 2 + 17) { it.toShort() }
        val frames = mutableListOf<ShortArray>()

        framer.append(input, 0, 4_096) { frames += it.copyOf() }
        framer.append(input, 4_096, 4_096) { frames += it.copyOf() }
        framer.append(input, 8_192, 17) { frames += it.copyOf() }

        assertEquals(25, frames.size)
        framer.finish { frames += it.copyOf() }

        assertEquals(26, frames.size)
        assertTrue(frames.all { it.size == 320 })
        val output = frames.flatMap { frame -> frame.toList() }
        assertEquals(input.toList(), output.take(input.size))
        assertTrue(output.drop(input.size).all { it == 0.toShort() })
    }
}

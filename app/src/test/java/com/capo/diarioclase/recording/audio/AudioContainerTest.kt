package com.capo.diarioclase.recording.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioContainerTest {
    @Test
    fun `detects controlled ready and open extensions`() {
        assertEquals(AudioContainer.WAV_PCM16, AudioContainer.fromPath("a.ready.wav"))
        assertEquals(AudioContainer.WAV_PCM16, AudioContainer.fromPath("a.open.wav"))
        assertEquals(AudioContainer.OGG_OPUS, AudioContainer.fromPath("a.ready.ogg"))
        assertEquals(AudioContainer.OGG_OPUS, AudioContainer.fromPath("a.open.ogg"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an unknown audio extension`() {
        AudioContainer.fromPath("a.mp3")
    }
}

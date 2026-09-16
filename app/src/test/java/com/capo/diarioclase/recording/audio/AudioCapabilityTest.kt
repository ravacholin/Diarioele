package com.capo.diarioclase.recording.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCapabilityTest {
    @Test
    fun `requires api encoder and decoder`() {
        assertEquals(
            AudioCapability.Unsupported(AudioCapabilityReason.API_TOO_OLD),
            decideOpusOggCapability(apiLevel = 28, encoderAvailable = true, decoderAvailable = true),
        )
        assertEquals(
            AudioCapability.Unsupported(AudioCapabilityReason.OPUS_ENCODER_MISSING),
            decideOpusOggCapability(apiLevel = 35, encoderAvailable = false, decoderAvailable = true),
        )
        assertEquals(
            AudioCapability.Unsupported(AudioCapabilityReason.OPUS_DECODER_MISSING),
            decideOpusOggCapability(apiLevel = 35, encoderAvailable = true, decoderAvailable = false),
        )
        assertEquals(
            AudioCapability.Supported,
            decideOpusOggCapability(apiLevel = 35, encoderAvailable = true, decoderAvailable = true),
        )
    }

    @Test
    fun `reports encoder before decoder when both codecs are missing`() {
        assertEquals(
            AudioCapability.Unsupported(AudioCapabilityReason.OPUS_ENCODER_MISSING),
            decideOpusOggCapability(apiLevel = 35, encoderAvailable = false, decoderAvailable = false),
        )
    }
}

package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.processing.transcription.AudioWindowReader
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingComponentFactoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `unsupported Opus capability stops recording with typed reason`() {
        val factory = factory(
            AudioCapability.Unsupported(AudioCapabilityReason.OPUS_ENCODER_MISSING),
        )

        try {
            factory.requireOpusSupport()
            fail("La grabación debía rechazarse")
        } catch (expected: UnsupportedAudioCapabilityException) {
            assertEquals(AudioCapabilityReason.OPUS_ENCODER_MISSING, expected.reason)
        }
    }

    @Test
    fun `supported Opus capability allows recording`() {
        factory(AudioCapability.Supported).requireOpusSupport()
    }

    private fun factory(capability: AudioCapability) = RecordingComponentFactory(
        root = folder.root,
        capabilityProbe = AudioCapabilityProbe { capability },
        encoderFactory = StreamingAudioEncoderFactory { _, _ -> error("unused") },
        inspector = EncodedAudioInspector { error("unused") },
        wavReader = AudioWindowReader { _, _ -> error("unused") },
        oggReader = AudioWindowReader { _, _ -> error("unused") },
    )
}

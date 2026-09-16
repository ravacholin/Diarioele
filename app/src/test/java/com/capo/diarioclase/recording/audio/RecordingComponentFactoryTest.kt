package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.processing.transcription.AudioWindowReader
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        var encoderCreated = false
        factory(
            capability = AudioCapability.Supported,
            encoderFactory = StreamingAudioEncoderFactory { output, _ ->
                encoderCreated = true
                ProbeEncoder(output)
            },
        ).requireOpusSupport()

        assertEquals(true, encoderCreated)
    }

    @Test
    fun `encoder initialization failure is reported as typed capability error`() {
        val factory = factory(
            capability = AudioCapability.Supported,
            encoderFactory = StreamingAudioEncoderFactory { _, _ -> error("No se pudo iniciar") },
        )

        try {
            factory.requireOpusSupport()
            fail("La inicialización real debía validarse")
        } catch (expected: UnsupportedAudioCapabilityException) {
            assertEquals(AudioCapabilityReason.CODEC_QUERY_FAILED, expected.reason)
        }
    }

    private fun factory(
        capability: AudioCapability,
        encoderFactory: StreamingAudioEncoderFactory = StreamingAudioEncoderFactory { output, _ ->
            ProbeEncoder(output)
        },
    ) = RecordingComponentFactory(
        root = folder.root,
        capabilityProbe = AudioCapabilityProbe { capability },
        encoderFactory = encoderFactory,
        inspector = EncodedAudioInspector { error("unused") },
        wavReader = AudioWindowReader { _, _ -> error("unused") },
        oggReader = AudioWindowReader { _, _ -> error("unused") },
    )
}

private class ProbeEncoder(output: File) : StreamingAudioEncoder {
    init {
        output.writeBytes(byteArrayOf(1))
    }

    override fun append(pcm: ShortArray, count: Int) = Unit
    override fun finish() = EncodedAudioInfo(1, 16_000, 1, "audio/opus")
    override fun close() = Unit
}

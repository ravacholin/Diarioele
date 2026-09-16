package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.processing.transcription.AudioWindowReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `supported Opus capability verifies a complete encoded file once`() {
        var encoderCreated = 0
        var inspected = 0
        val factory = factory(
            capability = AudioCapability.Supported,
            encoderFactory = StreamingAudioEncoderFactory { output, _ ->
                encoderCreated++
                ProbeEncoder(output)
            },
            inspector = EncodedAudioInspector {
                inspected++
                EncodedAudioInfo(100, 16_000, 1, "audio/opus")
            },
        )

        factory.requireOpusSupport()
        factory.requireOpusSupport()

        assertEquals(1, encoderCreated)
        assertEquals(1, inspected)
        assertFalse(folder.root.listFiles().orEmpty().any { it.name.startsWith(".opus-capability-") })
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

    @Test
    fun `encoder finalization failure is reported before a recording starts`() {
        val factory = factory(
            capability = AudioCapability.Supported,
            encoderFactory = StreamingAudioEncoderFactory { output, _ ->
                object : StreamingAudioEncoder {
                    init { output.writeBytes(byteArrayOf(1)) }
                    override fun append(pcm: ShortArray, count: Int) = Unit
                    override fun finish(): EncodedAudioInfo = error("No se pudo cerrar OGG")
                    override fun close() = Unit
                }
            },
        )

        try {
            factory.requireOpusSupport()
            fail("La prueba debía validar también el cierre del contenedor")
        } catch (expected: UnsupportedAudioCapabilityException) {
            assertEquals(AudioCapabilityReason.CODEC_QUERY_FAILED, expected.reason)
        }
    }

    private fun factory(
        capability: AudioCapability,
        encoderFactory: StreamingAudioEncoderFactory = StreamingAudioEncoderFactory { output, _ ->
            ProbeEncoder(output)
        },
        inspector: EncodedAudioInspector = EncodedAudioInspector {
            EncodedAudioInfo(100, 16_000, 1, "audio/opus")
        },
    ) = RecordingComponentFactory(
        root = folder.root,
        capabilityProbe = AudioCapabilityProbe { capability },
        encoderFactory = encoderFactory,
        inspector = inspector,
        wavReader = AudioWindowReader { _, _ -> error("unused") },
        oggReader = AudioWindowReader { _, _ -> error("unused") },
    )
}

private class ProbeEncoder(output: File) : StreamingAudioEncoder {
    private val output = output
    private var appended = false

    init {
        output.writeBytes(byteArrayOf(1))
    }

    override fun append(pcm: ShortArray, count: Int) {
        appended = count > 0
    }

    override fun finish(): EncodedAudioInfo {
        assertTrue(appended)
        assertTrue(output.length() > 0)
        return EncodedAudioInfo(100, 16_000, 1, "audio/opus")
    }
    override fun close() = Unit
}

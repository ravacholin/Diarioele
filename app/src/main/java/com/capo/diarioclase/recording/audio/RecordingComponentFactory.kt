package com.capo.diarioclase.recording.audio

import android.os.Build
import androidx.annotation.RequiresApi
import com.capo.diarioclase.processing.transcription.AndroidOpusWindowReader
import com.capo.diarioclase.processing.transcription.AudioWindowReader
import com.capo.diarioclase.processing.transcription.FormatAwareAudioWindowReader
import com.capo.diarioclase.processing.transcription.PcmWindowReader
import java.io.File

class UnsupportedAudioCapabilityException(
    val reason: AudioCapabilityReason,
) : IllegalStateException("La grabación OGG/Opus no está disponible: $reason")

@RequiresApi(Build.VERSION_CODES.Q)
class RecordingComponentFactory(
    private val root: File,
    private val capabilityProbe: AudioCapabilityProbe = AndroidOpusCapabilityProbe(),
    private val encoderFactory: StreamingAudioEncoderFactory = StreamingAudioEncoderFactory { output, config ->
        AndroidOpusEncoder(output, config)
    },
    private val inspector: EncodedAudioInspector = OggInspector(),
    wavReader: AudioWindowReader = AudioWindowReader(PcmWindowReader::read),
    oggReader: AudioWindowReader = AndroidOpusWindowReader(),
) {
    @Volatile
    private var opusVerified = false
    val cleanupFiles: CleanupFileStore = TemporaryAudioFiles(root)
    private val opusSegments: SegmentStore = OpusSegmentStore(
        root = root,
        encoderFactory = encoderFactory,
        inspector = inspector,
    )
    val rawSegments: SegmentStore = LegacyCompatibleSegmentStore(
        primary = opusSegments,
        legacy = FileSegmentStore(root),
        cleanup = cleanupFiles,
    )
    val windowReader: AudioWindowReader = FormatAwareAudioWindowReader(wavReader, oggReader)

    fun persistedSegments(metadata: SegmentMetadataStore): SegmentStore =
        PersistingSegmentStore(rawSegments, metadata)

    @Synchronized
    fun requireOpusSupport() {
        if (opusVerified) return
        when (val capability = capabilityProbe.opusOggSupport()) {
            AudioCapability.Supported -> Unit
            is AudioCapability.Unsupported ->
                throw UnsupportedAudioCapabilityException(capability.reason)
        }
        val probeFile = File(root, ".opus-capability-${System.nanoTime()}.ogg")
        var encoder: StreamingAudioEncoder? = null
        try {
            val config = AudioEncodingConfig()
            encoder = encoderFactory.create(probeFile, config)
            encoder.append(ShortArray(config.sampleRate / 10), config.sampleRate / 10)
            encoder.finish()
            val encoded = inspector.inspect(probeFile)
            check(encoded.durationMs > 0L)
            check(encoded.sampleRate == config.sampleRate)
            check(encoded.channelCount == config.channelCount)
            check(encoded.mimeType.equals("audio/opus", ignoreCase = true))
            opusVerified = true
        } catch (_: Exception) {
            throw UnsupportedAudioCapabilityException(AudioCapabilityReason.CODEC_QUERY_FAILED)
        } finally {
            encoder?.close()
            probeFile.delete()
        }
    }
}

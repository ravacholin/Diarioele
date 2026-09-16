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
    root: File,
    private val capabilityProbe: AudioCapabilityProbe = AndroidOpusCapabilityProbe(),
    encoderFactory: StreamingAudioEncoderFactory = StreamingAudioEncoderFactory { output, config ->
        AndroidOpusEncoder(output, config)
    },
    inspector: EncodedAudioInspector = OggInspector(),
    wavReader: AudioWindowReader = AudioWindowReader(PcmWindowReader::read),
    oggReader: AudioWindowReader = AndroidOpusWindowReader(),
) {
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

    fun requireOpusSupport() {
        when (val capability = capabilityProbe.opusOggSupport()) {
            AudioCapability.Supported -> Unit
            is AudioCapability.Unsupported ->
                throw UnsupportedAudioCapabilityException(capability.reason)
        }
    }
}

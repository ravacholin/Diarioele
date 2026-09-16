package com.capo.diarioclase.recording.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File

fun interface EncodedAudioInspector {
    fun inspect(file: File): EncodedAudioInfo
}

@RequiresApi(Build.VERSION_CODES.Q)
class OggInspector : EncodedAudioInspector {
    override fun inspect(file: File): EncodedAudioInfo {
        require(file.isFile && file.length() > 0L) { "El archivo OGG está vacío o no existe" }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) == true
            } ?: throw IllegalStateException("El contenedor no tiene una pista Opus")
            val format = extractor.getTrackFormat(trackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                measureDurationUs(extractor, trackIndex)
            }
            require(durationUs > 0L) { "La pista Opus no tiene duración válida" }
            EncodedAudioInfo(
                durationMs = durationUs / 1_000L,
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                mimeType = format.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("La pista Opus no informa su MIME"),
            )
        } finally {
            extractor.release()
        }
    }

    private fun measureDurationUs(extractor: MediaExtractor, trackIndex: Int): Long {
        extractor.selectTrack(trackIndex)
        var lastTimestampUs = -1L
        while (extractor.sampleTime >= 0L) {
            lastTimestampUs = extractor.sampleTime
            if (!extractor.advance()) break
        }
        return if (lastTimestampUs < 0L) 0L else lastTimestampUs + OPUS_FRAME_DURATION_US
    }

    private companion object {
        const val OPUS_FRAME_DURATION_US = 20_000L
    }
}

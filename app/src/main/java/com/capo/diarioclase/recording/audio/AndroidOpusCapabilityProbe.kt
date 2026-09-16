package com.capo.diarioclase.recording.audio

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

class AndroidOpusCapabilityProbe(
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val codecInfoProvider: () -> Array<MediaCodecInfo> = {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    },
) : AudioCapabilityProbe {
    override fun opusOggSupport(): AudioCapability {
        if (apiLevel < Build.VERSION_CODES.Q) {
            return AudioCapability.Unsupported(AudioCapabilityReason.API_TOO_OLD)
        }

        return runCatching {
            val codecInfos = codecInfoProvider()
            decideOpusOggCapability(
                apiLevel = apiLevel,
                encoderAvailable = codecInfos.any { it.isEncoder && it.supportsOpus() },
                decoderAvailable = codecInfos.any { !it.isEncoder && it.supportsOpus() },
            )
        }.getOrElse {
            AudioCapability.Unsupported(AudioCapabilityReason.CODEC_QUERY_FAILED)
        }
    }

    private fun MediaCodecInfo.supportsOpus(): Boolean =
        supportedTypes.any { mimeType ->
            mimeType.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
        }
}

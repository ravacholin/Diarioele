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
            val decoderFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                16_000,
                1,
            )
            val encoderFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                16_000,
                1,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 40_000)
                setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            }
            decideOpusOggCapability(
                apiLevel = apiLevel,
                encoderAvailable = codecInfos.any { it.isEncoder && it.supports(encoderFormat) },
                decoderAvailable = codecInfos.any { !it.isEncoder && it.supports(decoderFormat) },
            )
        }.getOrElse {
            AudioCapability.Unsupported(AudioCapabilityReason.CODEC_QUERY_FAILED)
        }
    }

    private fun MediaCodecInfo.supports(format: MediaFormat): Boolean {
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        val supportedMime = supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) }
            ?: return false
        return runCatching { getCapabilitiesForType(supportedMime).isFormatSupported(format) }
            .getOrDefault(false)
    }
}

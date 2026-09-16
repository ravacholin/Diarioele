package com.capo.diarioclase.recording.audio

sealed interface AudioCapability {
    data object Supported : AudioCapability

    data class Unsupported(
        val reason: AudioCapabilityReason,
    ) : AudioCapability
}

enum class AudioCapabilityReason {
    API_TOO_OLD,
    OPUS_ENCODER_MISSING,
    OPUS_DECODER_MISSING,
    CODEC_QUERY_FAILED,
}

fun interface AudioCapabilityProbe {
    fun opusOggSupport(): AudioCapability
}

fun decideOpusOggCapability(
    apiLevel: Int,
    encoderAvailable: Boolean,
    decoderAvailable: Boolean,
): AudioCapability = when {
    apiLevel < MIN_OGG_OPUS_API -> AudioCapability.Unsupported(AudioCapabilityReason.API_TOO_OLD)
    !encoderAvailable -> AudioCapability.Unsupported(AudioCapabilityReason.OPUS_ENCODER_MISSING)
    !decoderAvailable -> AudioCapability.Unsupported(AudioCapabilityReason.OPUS_DECODER_MISSING)
    else -> AudioCapability.Supported
}

private const val MIN_OGG_OPUS_API = 29

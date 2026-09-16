package com.capo.diarioclase.recording.audio

enum class AudioContainer {
    WAV_PCM16,
    OGG_OPUS;

    companion object {
        fun fromPath(path: String): AudioContainer = when {
            path.endsWith(".wav", ignoreCase = true) -> WAV_PCM16
            path.endsWith(".ogg", ignoreCase = true) -> OGG_OPUS
            else -> throw IllegalArgumentException("Formato de audio temporal no compatible")
        }
    }
}

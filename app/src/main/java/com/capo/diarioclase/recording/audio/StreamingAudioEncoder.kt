package com.capo.diarioclase.recording.audio

import java.io.File

data class AudioEncodingConfig(
    val sampleRate: Int = 16_000,
    val channelCount: Int = 1,
    val bitRate: Int = 40_000,
)

data class EncodedAudioInfo(
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val mimeType: String,
)

interface StreamingAudioEncoder : AutoCloseable {
    fun append(pcm: ShortArray, count: Int)

    fun finish(): EncodedAudioInfo

    override fun close()
}

fun interface StreamingAudioEncoderFactory {
    fun create(output: File, config: AudioEncodingConfig): StreamingAudioEncoder
}

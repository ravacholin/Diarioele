package com.capo.diarioclase.recording.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RequiresApi(Build.VERSION_CODES.Q)
class AndroidOpusEncoder(
    output: File,
    private val config: AudioEncodingConfig = AudioEncodingConfig(),
) : StreamingAudioEncoder {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()

    private var muxerTrackIndex = -1
    private var muxerStarted = false
    private var acceptedSamples = 0L
    private var released = false
    private var finishedInfo: EncodedAudioInfo? = null

    init {
        require(config.sampleRate > 0)
        require(config.channelCount == 1) {
            "La grabación Opus temporal requiere audio mono"
        }
        require(config.bitRate > 0)
        output.parentFile?.mkdirs()
        require(!output.exists()) { "El archivo OGG de destino ya existe" }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            config.sampleRate,
            config.channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        }
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(format)
            ?: throw IllegalStateException("No hay un encoder Opus compatible")

        var createdCodec: MediaCodec? = null
        var createdMuxer: MediaMuxer? = null
        try {
            createdCodec = MediaCodec.createByCodecName(codecName).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            createdMuxer = MediaMuxer(
                output.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
            )
        } catch (error: Throwable) {
            runCatching { createdCodec?.stop() }
            runCatching { createdCodec?.release() }
            runCatching { createdMuxer?.release() }
            throw error
        }
        codec = requireNotNull(createdCodec)
        muxer = requireNotNull(createdMuxer)
    }

    @Synchronized
    override fun append(pcm: ShortArray, count: Int) {
        checkOpen()
        require(count in 0..pcm.size)

        var offset = 0
        while (offset < count) {
            val inputIndex = awaitInputBuffer()
            val inputBuffer = codec.getInputBuffer(inputIndex)
                ?: throw IllegalStateException("El encoder Opus no entregó el buffer de entrada")
            inputBuffer.clear()
            inputBuffer.order(ByteOrder.nativeOrder())
            val sampleCount = minOf(count - offset, inputBuffer.remaining() / Short.SIZE_BYTES)
            check(sampleCount > 0) { "El buffer de entrada Opus no admite una muestra PCM16" }
            inputBuffer.asShortBuffer().put(pcm, offset, sampleCount)
            val presentationTimeUs = samplesToMicros(acceptedSamples)
            codec.queueInputBuffer(
                inputIndex,
                0,
                sampleCount * Short.SIZE_BYTES,
                presentationTimeUs,
                0,
            )
            acceptedSamples += sampleCount
            offset += sampleCount
            drain(endOfStream = false)
        }
    }

    @Synchronized
    override fun finish(): EncodedAudioInfo {
        finishedInfo?.let { return it }
        checkOpen()

        return try {
            queueEndOfStream()
            drain(endOfStream = true)
            check(muxerStarted) { "El encoder Opus no produjo un formato de salida" }
            muxer.stop()
            codec.stop()
            EncodedAudioInfo(
                durationMs = acceptedSamples * 1_000L / config.sampleRate,
                sampleRate = config.sampleRate,
                channelCount = config.channelCount,
                mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS,
            ).also { finishedInfo = it }
        } finally {
            releaseResources()
        }
    }

    @Synchronized
    override fun close() {
        releaseResources()
    }

    private fun awaitInputBuffer(): Int {
        val deadline = System.nanoTime() + CODEC_DEADLINE_NS
        while (true) {
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index >= 0) return index
            drain(endOfStream = false)
            if (System.nanoTime() >= deadline) {
                throw IllegalStateException("Tiempo agotado esperando entrada del encoder Opus")
            }
        }
    }

    private fun queueEndOfStream() {
        val inputIndex = awaitInputBuffer()
        codec.queueInputBuffer(
            inputIndex,
            0,
            0,
            samplesToMicros(acceptedSamples),
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
    }

    private fun drain(endOfStream: Boolean) {
        val deadline = System.nanoTime() + CODEC_DEADLINE_NS
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (System.nanoTime() >= deadline) {
                        throw IllegalStateException("Tiempo agotado cerrando el encoder Opus")
                    }
                }

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "El formato Opus cambió más de una vez" }
                    muxerTrackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }

                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> if (outputIndex >= 0) {
                    writeOutput(outputIndex)
                    val reachedEnd = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (reachedEnd) return
                }
            }
        }
    }

    private fun writeOutput(outputIndex: Int) {
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            return
        }
        if (bufferInfo.size == 0) return
        check(muxerStarted) { "El encoder produjo audio antes del formato Opus" }
        val outputBuffer = codec.getOutputBuffer(outputIndex)
            ?: throw IllegalStateException("El encoder Opus no entregó el buffer de salida")
        outputBuffer.position(bufferInfo.offset)
        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
        muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
    }

    private fun checkOpen() {
        check(!released) { "El encoder Opus ya está cerrado" }
        check(finishedInfo == null) { "El encoder Opus ya terminó" }
    }

    private fun samplesToMicros(samples: Long): Long =
        samples * 1_000_000L / config.sampleRate

    private fun releaseResources() {
        if (released) return
        released = true
        runCatching { muxer.release() }
        runCatching { codec.release() }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 8_192
        const val CODEC_TIMEOUT_US = 10_000L
        val CODEC_DEADLINE_NS: Long = TimeUnit.SECONDS.toNanos(10)
    }
}

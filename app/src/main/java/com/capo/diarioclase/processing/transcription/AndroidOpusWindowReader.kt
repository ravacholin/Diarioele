package com.capo.diarioclase.processing.transcription

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.min

@RequiresApi(Build.VERSION_CODES.Q)
class AndroidOpusWindowReader(
    private val targetSampleRate: Int = 16_000,
) : AudioWindowReader {
    override fun read(file: File, plan: AudioWindowPlan): FloatArray {
        require(file.isFile) { "El archivo OGG no existe" }
        require(plan.startMs >= 0L) { "El inicio de la ventana no puede ser negativo" }
        require(plan.endMs >= plan.startMs) { "La ventana de audio es inválida" }
        require(targetSampleRate > 0) { "La frecuencia de Whisper debe ser positiva" }

        val startUs = plan.startMs * 1_000L
        val endUs = plan.endMs * 1_000L
        val expectedSamples = ((plan.endMs - plan.startMs) * targetSampleRate / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (expectedSamples == 0) return FloatArray(0)

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderStarted = false
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = opusTrackIndex(extractor)
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(trackFormat)
                ?: throw IllegalStateException("No hay un decoder Opus compatible")
            trackFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            decoder = MediaCodec.createByCodecName(decoderName).apply {
                configure(trackFormat, null, null, 0)
                start()
            }
            decoderStarted = true

            val decoded = decodeWindow(
                extractor = extractor,
                decoder = decoder,
                startUs = startUs,
                endUs = endUs,
            )
            val mono = PcmNormalizer.toMono(decoded.samples, decoded.channelCount)
            val resampled = PcmNormalizer.resampleMono(
                input = mono,
                sourceSampleRate = decoded.sampleRate,
                targetSampleRate = targetSampleRate,
            )
            return PcmNormalizer.toFloat(resampled).copyOf(expectedSamples)
        } finally {
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun decodeWindow(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        startUs: Long,
        endUs: Long,
    ): DecodedPcm {
        val bufferInfo = MediaCodec.BufferInfo()
        val accumulator = ShortAccumulator()
        var sampleRate = 0
        var channelCount = 0
        var inputEnded = false
        var outputEnded = false
        val deadline = System.nanoTime() + DECODE_DEADLINE_NS

        while (!outputEnded) {
            if (System.nanoTime() >= deadline) {
                throw IllegalStateException("Tiempo agotado decodificando la ventana Opus")
            }

            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs < 0L || sampleTimeUs > endUs) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            endUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEnded = true
                    } else {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("El decoder Opus no entregó entrada")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                sampleTimeUs.coerceAtLeast(0L),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = decoder.outputFormat
                    val pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    check(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                        "El decoder Opus no produjo PCM16"
                    }
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }

                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> if (outputIndex >= 0) {
                    check(sampleRate > 0 && channelCount > 0) {
                        "El decoder Opus produjo audio sin formato"
                    }
                    appendOverlappingFrames(
                        decoder = decoder,
                        outputIndex = outputIndex,
                        info = bufferInfo,
                        sampleRate = sampleRate,
                        channelCount = channelCount,
                        startUs = startUs,
                        endUs = endUs,
                        destination = accumulator,
                    )
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }

        check(sampleRate > 0 && channelCount > 0) { "La ventana Opus no produjo PCM" }
        return DecodedPcm(accumulator.toArray(), sampleRate, channelCount)
    }

    private fun appendOverlappingFrames(
        decoder: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
        sampleRate: Int,
        channelCount: Int,
        startUs: Long,
        endUs: Long,
        destination: ShortAccumulator,
    ) {
        if (info.size <= 0) return
        val bytesPerFrame = channelCount * Short.SIZE_BYTES
        val frameCount = info.size / bytesPerFrame
        if (frameCount <= 0) return

        val firstFrame = framesUntil(startUs - info.presentationTimeUs, sampleRate)
            .coerceIn(0, frameCount)
        val endFrameExclusive = framesUntil(endUs - info.presentationTimeUs, sampleRate)
            .coerceIn(0, frameCount)
        if (endFrameExclusive <= firstFrame) return

        val outputBuffer = decoder.getOutputBuffer(outputIndex)
            ?: throw IllegalStateException("El decoder Opus no entregó salida")
        outputBuffer.position(info.offset)
        outputBuffer.limit(info.offset + info.size)
        outputBuffer.order(ByteOrder.nativeOrder())
        val shorts = outputBuffer.asShortBuffer()
        shorts.position(firstFrame * channelCount)
        repeat((endFrameExclusive - firstFrame) * channelCount) {
            destination.add(shorts.get())
        }
    }

    private fun framesUntil(deltaUs: Long, sampleRate: Int): Int {
        if (deltaUs <= 0L) return 0
        return min(
            Int.MAX_VALUE.toLong(),
            (deltaUs * sampleRate + 999_999L) / 1_000_000L,
        ).toInt()
    }

    private fun opusTrackIndex(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) == true
        } ?: throw IllegalStateException("El contenedor no tiene una pista Opus")

    private data class DecodedPcm(
        val samples: ShortArray,
        val sampleRate: Int,
        val channelCount: Int,
    )

    private class ShortAccumulator {
        private var values = ShortArray(INITIAL_ACCUMULATOR_SIZE)
        private var size = 0

        fun add(value: Short) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): ShortArray = values.copyOf(size)
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val INITIAL_ACCUMULATOR_SIZE = 16_000
        val DECODE_DEADLINE_NS: Long = TimeUnit.SECONDS.toNanos(20)
    }
}

package com.capo.diarioclase.processing.transcription

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object PcmNormalizer {
    fun toMono(interleaved: ShortArray, channelCount: Int): ShortArray {
        require(channelCount > 0) { "La cantidad de canales debe ser positiva" }
        require(interleaved.size % channelCount == 0) {
            "El PCM intercalado no contiene cuadros completos"
        }
        if (channelCount == 1) return interleaved.copyOf()

        return ShortArray(interleaved.size / channelCount) { frameIndex ->
            var sum = 0L
            val firstSample = frameIndex * channelCount
            repeat(channelCount) { channel ->
                sum += interleaved[firstSample + channel]
            }
            (sum / channelCount)
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toShort()
        }
    }

    fun resampleMono(
        input: ShortArray,
        sourceSampleRate: Int,
        targetSampleRate: Int,
    ): ShortArray {
        require(sourceSampleRate > 0) { "La frecuencia de origen debe ser positiva" }
        require(targetSampleRate > 0) { "La frecuencia de destino debe ser positiva" }
        if (input.isEmpty()) return ShortArray(0)
        if (sourceSampleRate == targetSampleRate) return input.copyOf()

        val outputSize = (
            (input.size.toLong() * targetSampleRate + sourceSampleRate / 2L) /
                sourceSampleRate
            ).toInt()
        val rateRatio = targetSampleRate.toDouble() / sourceSampleRate
        val cutoff = 0.5 * min(1.0, rateRatio) * CUTOFF_GUARD

        return ShortArray(outputSize) { outputIndex ->
            val sourcePosition = outputIndex.toDouble() / rateRatio
            val center = floor(sourcePosition).toInt()
            var weightedSum = 0.0
            var weightSum = 0.0

            for (inputIndex in (center - FILTER_RADIUS + 1)..(center + FILTER_RADIUS)) {
                if (inputIndex !in input.indices) continue
                val distance = sourcePosition - inputIndex
                if (abs(distance) >= FILTER_RADIUS) continue
                val sincPosition = 2.0 * cutoff * distance
                val sinc = if (abs(sincPosition) < SINC_EPSILON) {
                    1.0
                } else {
                    sin(PI * sincPosition) / (PI * sincPosition)
                }
                val window = 0.5 * (1.0 + cos(PI * distance / FILTER_RADIUS))
                val weight = 2.0 * cutoff * sinc * window
                weightedSum += input[inputIndex] * weight
                weightSum += weight
            }

            val sample = if (abs(weightSum) < SINC_EPSILON) 0.0 else weightedSum / weightSum
            sample.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    fun toFloat(input: ShortArray): FloatArray =
        FloatArray(input.size) { index -> input[index] / 32_768f }

    private const val FILTER_RADIUS = 16
    private const val CUTOFF_GUARD = 0.94
    private const val SINC_EPSILON = 1e-12
}

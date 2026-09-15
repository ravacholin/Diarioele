package com.capo.diarioclase.processing.transcription

import com.capo.diarioclase.recording.audio.BITS_PER_SAMPLE
import com.capo.diarioclase.recording.audio.CHANNELS
import com.capo.diarioclase.recording.audio.SAMPLE_RATE
import com.capo.diarioclase.recording.audio.WAV_HEADER_BYTES
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

data class AudioWindowPlan(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val confirmedUntilMs: Long,
)

object AudioWindowPlanner {
    const val WINDOW_MS = 30_000L
    const val OVERLAP_MS = 2_000L

    fun plan(durationMs: Long): List<AudioWindowPlan> {
        if (durationMs <= 0L) return emptyList()

        val windows = mutableListOf<AudioWindowPlan>()
        var startMs = 0L
        var index = 0
        while (startMs < durationMs) {
            val endMs = min(startMs + WINDOW_MS, durationMs)
            val confirmedUntilMs = if (endMs == durationMs) endMs else endMs - OVERLAP_MS
            windows += AudioWindowPlan(
                index = index++,
                startMs = startMs,
                endMs = endMs,
                confirmedUntilMs = confirmedUntilMs,
            )
            startMs = confirmedUntilMs
        }
        return windows
    }
}

object PcmWindowReader {
    fun read(file: File, plan: AudioWindowPlan): FloatArray {
        require(plan.startMs >= 0L) { "El inicio de la ventana no puede ser negativo" }
        require(plan.endMs >= plan.startMs) { "La ventana de audio es inválida" }

        return RandomAccessFile(file, "r").use { input ->
            validateCanonicalPcm16Wav(input)

            val declaredDataBytes = input.readIntLeAt(40).toLong() and 0xffffffffL
            val availableDataBytes = (input.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)
            require(declaredDataBytes <= availableDataBytes) { "El WAV está truncado" }

            val bytesPerSample = CHANNELS * BITS_PER_SAMPLE / 8
            val firstSample = plan.startMs * SAMPLE_RATE / 1_000L
            val lastSample = plan.endMs * SAMPLE_RATE / 1_000L
            val totalSamples = declaredDataBytes / bytesPerSample

            require(firstSample <= totalSamples && lastSample <= totalSamples) {
                "La ventana excede la duración del WAV"
            }

            val sampleCountLong = lastSample - firstSample
            require(sampleCountLong <= Int.MAX_VALUE) { "La ventana es demasiado grande" }
            val sampleCount = sampleCountLong.toInt()
            val samples = FloatArray(sampleCount)
            // Lectura en bloque: una sola llamada de E/S en vez de dos por muestra
            // (antes ~960 000 llamadas para 30 s). Decodifica PCM16 little-endian.
            val rawBytes = ByteArray(sampleCount * bytesPerSample)
            input.seek(WAV_HEADER_BYTES + firstSample * bytesPerSample)
            input.readFully(rawBytes)
            val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until sampleCount) {
                samples[index] = buffer.short.toFloat() / 32_768f
            }
            samples
        }
    }

    private fun validateCanonicalPcm16Wav(input: RandomAccessFile) {
        require(input.length() >= WAV_HEADER_BYTES) { "El archivo no contiene un encabezado WAV completo" }
        require(input.readAsciiAt(0, 4) == "RIFF") { "Falta la cabecera RIFF" }
        require(input.readAsciiAt(8, 4) == "WAVE") { "Falta la cabecera WAVE" }
        require(input.readAsciiAt(36, 4) == "data") { "El WAV no usa el formato canónico esperado" }
        require(input.readShortLeAt(20) == 1) { "El WAV no contiene PCM lineal" }
        require(input.readShortLeAt(22) == CHANNELS) { "El WAV debe ser mono" }
        require(input.readIntLeAt(24) == SAMPLE_RATE) { "El WAV debe usar 16 kHz" }
        require(input.readShortLeAt(34) == BITS_PER_SAMPLE) { "El WAV debe usar PCM de 16 bits" }
    }

    private fun RandomAccessFile.readAsciiAt(offset: Long, length: Int): String {
        seek(offset)
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readShortLeAt(offset: Long): Int {
        seek(offset)
        val low = read()
        val high = read()
        require(low >= 0 && high >= 0) { "Encabezado WAV incompleto" }
        return low or (high shl 8)
    }

    private fun RandomAccessFile.readIntLeAt(offset: Long): Int {
        seek(offset)
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        require(b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) { "Encabezado WAV incompleto" }
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}

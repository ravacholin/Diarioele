package com.capo.diarioclase.processing.transcription

import com.capo.diarioclase.recording.audio.CHANNELS
import com.capo.diarioclase.recording.audio.LEGACY_PCM16_BYTES_PER_SAMPLE
import com.capo.diarioclase.recording.audio.MuLawCodec
import com.capo.diarioclase.recording.audio.SAMPLE_RATE
import com.capo.diarioclase.recording.audio.STORED_BITS_PER_SAMPLE
import com.capo.diarioclase.recording.audio.STORED_BYTES_PER_SAMPLE
import com.capo.diarioclase.recording.audio.WAVE_FORMAT_MULAW
import com.capo.diarioclase.recording.audio.WAVE_FORMAT_PCM
import java.io.File
import java.io.RandomAccessFile
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
            val data = locateData(input)

            val declaredDataBytes = data.byteLength
            val availableDataBytes = (input.length() - data.offset).coerceAtLeast(0L)
            require(declaredDataBytes <= availableDataBytes) { "El WAV está truncado" }

            val bytesPerSample = data.bytesPerSample.toLong()
            val firstSample = plan.startMs * SAMPLE_RATE / 1_000L
            val lastSample = plan.endMs * SAMPLE_RATE / 1_000L
            val totalSamples = declaredDataBytes / bytesPerSample

            require(firstSample <= totalSamples && lastSample <= totalSamples) {
                "La ventana excede la duración del WAV"
            }

            val sampleCountLong = lastSample - firstSample
            require(sampleCountLong <= Int.MAX_VALUE / data.bytesPerSample) { "La ventana es demasiado grande" }
            val sampleCount = sampleCountLong.toInt()
            val samples = FloatArray(sampleCount)
            // Lectura en bloque: una sola llamada de E/S. µ-law se decodifica a PCM16; los
            // segmentos PCM16 heredados se leen tal cual. Misma normalización en ambos casos.
            val rawBytes = ByteArray(sampleCount * data.bytesPerSample)
            input.seek(data.offset + firstSample * bytesPerSample)
            input.readFully(rawBytes)
            if (data.legacyPcm16) {
                for (index in 0 until sampleCount) {
                    val low = rawBytes[index * 2].toInt() and 0xff
                    val high = rawBytes[index * 2 + 1].toInt()
                    samples[index] = ((high shl 8) or low).toShort().toFloat() / 32_768f
                }
            } else {
                for (index in 0 until sampleCount) {
                    samples[index] = MuLawCodec.decode(rawBytes[index]).toFloat() / 32_768f
                }
            }
            samples
        }
    }

    private data class DataChunk(val offset: Long, val byteLength: Long, val legacyPcm16: Boolean) {
        val bytesPerSample get() = if (legacyPcm16) LEGACY_PCM16_BYTES_PER_SAMPLE else STORED_BYTES_PER_SAMPLE
    }

    /**
     * Recorre los bloques RIFF, valida el `fmt ` y devuelve la ubicación del bloque `data`.
     * Acepta µ-law de 8 bits y también PCM16, el formato de los segmentos grabados antes de
     * la migración a µ-law que pueden seguir pendientes de transcripción.
     */
    private fun locateData(input: RandomAccessFile): DataChunk {
        val length = input.length()
        require(length >= 12L) { "El archivo no contiene un encabezado WAV completo" }
        require(input.readAsciiAt(0, 4) == "RIFF") { "Falta la cabecera RIFF" }
        require(input.readAsciiAt(8, 4) == "WAVE") { "Falta la cabecera WAVE" }

        var position = 12L
        var legacyPcm16: Boolean? = null
        var dataChunk: Pair<Long, Long>? = null
        while (position + 8L <= length) {
            val id = input.readAsciiAt(position, 4)
            val size = input.readIntLeAt(position + 4L).toLong() and 0xffffffffL
            val body = position + 8L
            when (id) {
                "fmt " -> {
                    val format = input.readShortLeAt(body)
                    require(format == WAVE_FORMAT_MULAW || format == WAVE_FORMAT_PCM) {
                        "El WAV no usa µ-law (G.711) ni PCM16"
                    }
                    val legacy = format == WAVE_FORMAT_PCM
                    require(input.readShortLeAt(body + 2L) == CHANNELS) { "El WAV debe ser mono" }
                    require(input.readIntLeAt(body + 4L) == SAMPLE_RATE) { "El WAV debe usar 16 kHz" }
                    val bits = if (legacy) LEGACY_PCM16_BYTES_PER_SAMPLE * 8 else STORED_BITS_PER_SAMPLE
                    require(input.readShortLeAt(body + 14L) == bits) { "El WAV debe usar µ-law de 8 bits o PCM de 16 bits" }
                    legacyPcm16 = legacy
                }
                "data" -> dataChunk = body to size
            }
            if (legacyPcm16 != null && dataChunk != null) break
            position = body + size + (size and 1L)
        }
        val legacy = requireNotNull(legacyPcm16) { "El WAV no contiene un bloque fmt válido" }
        val (offset, size) = dataChunk ?: throw IllegalArgumentException("El WAV no contiene datos de audio")
        return DataChunk(offset, size, legacy)
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

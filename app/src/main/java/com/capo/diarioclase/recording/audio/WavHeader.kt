package com.capo.diarioclase.recording.audio

/**
 * Cabecera WAV µ-law (G.711) de tamaño fijo. Formato no-PCM: bloque `fmt ` de 18 bytes con
 * `audioFormat = 7` (WAVE_FORMAT_MULAW), 8 bits por muestra, más un bloque `fact` con el número
 * de muestras (obligatorio para formatos no-PCM). El cuerpo se agrega después y los tamaños se
 * parchean al cerrar el segmento.
 */
class WavHeader private constructor(private val dataBytes: Int) {
    fun encode(): ByteArray = ByteArray(WAV_HEADER_BYTES.toInt()).also { out ->
        // RIFF: tamaño = archivo total - 8 = (cabecera 58 - 8) + dataBytes = 50 + dataBytes
        out.putAscii(0, "RIFF"); out.putIntLe(4, 50 + dataBytes); out.putAscii(8, "WAVE")
        // fmt (18 bytes de cuerpo, WAVEFORMATEX con cbSize = 0)
        out.putAscii(12, "fmt "); out.putIntLe(16, 18); out.putShortLe(20, WAVE_FORMAT_MULAW)
        out.putShortLe(22, CHANNELS); out.putIntLe(24, SAMPLE_RATE)
        out.putIntLe(28, SAMPLE_RATE * CHANNELS * STORED_BYTES_PER_SAMPLE)
        out.putShortLe(32, CHANNELS * STORED_BYTES_PER_SAMPLE); out.putShortLe(34, STORED_BITS_PER_SAMPLE)
        out.putShortLe(36, 0)
        // fact: cantidad de muestras (1 byte = 1 muestra en mono µ-law)
        out.putAscii(38, "fact"); out.putIntLe(42, 4); out.putIntLe(46, dataBytes / STORED_BYTES_PER_SAMPLE)
        // data
        out.putAscii(50, "data"); out.putIntLe(54, dataBytes)
    }
    companion object { fun forMuLaw(dataBytes: Int) = WavHeader(dataBytes.coerceAtLeast(0)) }
}
const val SAMPLE_RATE = 16_000
const val CHANNELS = 1
const val WAVE_FORMAT_MULAW = 7
const val STORED_BITS_PER_SAMPLE = 8
const val STORED_BYTES_PER_SAMPLE = 1
const val WAV_HEADER_BYTES = 58L
private fun ByteArray.putAscii(i:Int,s:String)=s.forEachIndexed{x,c->this[i+x]=c.code.toByte()}
private fun ByteArray.putShortLe(i:Int,v:Int){this[i]=v.toByte();this[i+1]=(v ushr 8).toByte()}
private fun ByteArray.putIntLe(i:Int,v:Int){this[i]=v.toByte();this[i+1]=(v ushr 8).toByte();this[i+2]=(v ushr 16).toByte();this[i+3]=(v ushr 24).toByte()}

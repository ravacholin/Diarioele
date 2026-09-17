package com.capo.diarioclase.recording.audio

/**
 * Códec µ-law (G.711) para almacenar el audio a 8 bits por muestra en vez de 16.
 *
 * No es compresión con códec: es una re-cuantización por muestra (logarítmica, pensada para
 * voz) que ocupa la mitad de espacio con cómputo mínimo —una operación de bits al codificar y
 * una búsqueda en tabla de 256 entradas al decodificar—. El audio se guarda en µ-law y se
 * decodifica a PCM16 antes de entregárselo a Whisper, que sigue recibiendo el mismo audio.
 */
object MuLawCodec {
    private const val BIAS = 0x84
    private const val CLIP = 32_635

    /** PCM lineal de 16 bits → un byte µ-law. */
    fun encode(sample: Short): Byte {
        var magnitude = sample.toInt()
        val sign = (magnitude shr 8) and 0x80
        if (sign != 0) magnitude = -magnitude
        if (magnitude > CLIP) magnitude = CLIP
        magnitude += BIAS
        val index = (magnitude shr 7) and 0xFF
        val exponent = if (index == 0) 0 else 31 - Integer.numberOfLeadingZeros(index)
        val mantissa = (magnitude shr (exponent + 3)) and 0x0F
        val encoded = (sign or (exponent shl 4) or mantissa).inv() and 0xFF
        return encoded.toByte()
    }

    /** Byte µ-law → PCM lineal de 16 bits (búsqueda en tabla precomputada). */
    fun decode(value: Byte): Short = DECODE_TABLE[value.toInt() and 0xFF]

    private val DECODE_TABLE: ShortArray = ShortArray(256) { decodeValue(it) }

    private fun decodeValue(encoded: Int): Short {
        val inverted = encoded.inv() and 0xFF
        val sign = inverted and 0x80
        val exponent = (inverted shr 4) and 0x07
        val mantissa = inverted and 0x0F
        var sample = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        if (sign != 0) sample = -sample
        return sample.toShort()
    }
}

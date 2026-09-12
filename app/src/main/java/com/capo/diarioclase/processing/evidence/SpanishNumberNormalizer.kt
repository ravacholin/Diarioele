package com.capo.diarioclase.processing.evidence

import java.text.Normalizer

object SpanishNumberNormalizer {
    private val units = mapOf("cero" to 0, "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9)
    private val teens = mapOf("diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19)
    private val tens = mapOf("veinte" to 20, "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90)
    private val hundreds = mapOf("cien" to 100, "ciento" to 100, "doscientos" to 200, "trescientos" to 300, "cuatrocientos" to 400, "quinientos" to 500, "seiscientos" to 600, "setecientos" to 700, "ochocientos" to 800, "novecientos" to 900)

    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    fun readPrefix(text: String): Pair<Int, Int>? {
        val source = normalize(text)
        Regex("^\\d+").find(source)?.let { return it.value.toInt() to it.range.last + 1 }
        val tokens = source.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        var value = 0
        var used = 0
        hundreds[tokens[0]]?.let { value += it; used++ }
        if (used < tokens.size) {
            val token = tokens[used]
            when {
                token in teens -> { value += teens.getValue(token); used++ }
                token.startsWith("veinti") && token.removePrefix("veinti") in units -> { value += 20 + units.getValue(token.removePrefix("veinti")); used++ }
                token in tens -> {
                    value += tens.getValue(token); used++
                    if (used + 1 < tokens.size && tokens[used] == "y" && tokens[used + 1] in units) { value += units.getValue(tokens[used + 1]); used += 2 }
                }
                token in units -> { value += units.getValue(token); used++ }
            }
        }
        return if (used == 0) null else value to tokens.take(used).joinToString(" ").length
    }
}

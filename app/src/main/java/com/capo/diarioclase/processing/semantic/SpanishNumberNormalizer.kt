package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.evidence.SpanishNumberNormalizer as WordNumbers

/** Tipo de número de aula que se extrae de la transcripción. */
enum class NumberKind { PAGE, EXERCISE }

/**
 * Normalizador numérico de aula del quality loop (Task Q1).
 *
 * Extrae los números de página y de ejercicio efectivamente mencionados en la transcripción,
 * anclados por su palabra clave ("página" / "ejercicio"). Sin la palabra clave no extrae nada,
 * así un teléfono, una hora o un año sueltos no se confunden con una página. Reconoce:
 *
 * - palabras: "cuarenta y dos" → "42", "ciento cinco" → "105";
 * - dígitos: "12" → "12";
 * - rangos: "tres a cinco" / "del 3 al 5" → "3", "4", "5";
 * - listas: "3, 4 y 7" → "3", "4", "7";
 * - alfanuméricos: "4b" → "4b".
 *
 * Reutiliza el lector de números en palabras de la Fase 4
 * ([com.capo.diarioclase.processing.evidence.SpanishNumberNormalizer]) para no duplicar el
 * mapa de unidades, decenas y centenas. No usa red.
 *
 * El gate de calidad (Q1) lo usa de dos formas: [values] sobre el texto de los spans para saber
 * qué números aparecen realmente en la evidencia, y [bareValues] sobre el valor de un claim para
 * leer el número que ese claim afirma, sin exigir palabra clave.
 */
class SpanishNumberNormalizer {

    /** Números de [kind] mencionados en [text], anclados por su palabra clave. */
    fun values(text: String, kind: NumberKind): List<String> {
        val normalized = WordNumbers.normalize(text)
        val keyword = when (kind) {
            NumberKind.PAGE -> PAGE_KEYWORD
            NumberKind.EXERCISE -> EXERCISE_KEYWORD
        }
        val out = LinkedHashSet<String>()
        keyword.findAll(normalized).forEach { match ->
            out += parse(normalized.substring(match.range.last + 1))
        }
        return out.toList()
    }

    /** Números leídos directamente de [text] (p. ej. el valor propio de un claim), sin palabra clave. */
    fun bareValues(text: String, kind: NumberKind): List<String> {
        // El [kind] no cambia la lectura de un valor suelto; se acepta por simetría con [values]
        // y para dejar espacio a reglas específicas por tipo en versiones futuras.
        return parse(WordNumbers.normalize(text))
    }

    private fun parse(tail: String): List<String> {
        var rem = clean(tail)
        val out = LinkedHashSet<String>()
        while (rem.isNotEmpty()) {
            rem = stripLeadingFillers(rem)
            val first = readOneNumber(rem) ?: break
            rem = rem.substring(first.consumed)

            val after = rem.trimStart()
            val range = RANGE_CONNECTOR.find(after)
            if (first.numeric != null && range != null) {
                val secondPart = after.substring(range.range.last + 1)
                val second = readOneNumber(secondPart)
                if (second?.numeric != null &&
                    second.numeric >= first.numeric &&
                    second.numeric - first.numeric <= MAX_RANGE
                ) {
                    (first.numeric..second.numeric).forEach { out += it.toString() }
                    rem = secondPart.substring(second.consumed)
                    continue
                }
            }
            out += first.value
        }
        return out.toList()
    }

    private fun stripLeadingFillers(text: String): String {
        var rem = text.trimStart()
        while (true) {
            val match = LEADING_FILLER.find(rem) ?: break
            rem = rem.substring(match.range.last + 1).trimStart()
        }
        return rem
    }

    private fun readOneNumber(text: String): NumberToken? {
        DIGIT_TOKEN.find(text)?.let { match ->
            val token = match.value
            val digits = token.takeWhile { it.isDigit() }
            return NumberToken(value = token, numeric = digits.toIntOrNull(), consumed = token.length)
        }
        val prefix = WordNumbers.readPrefix(text) ?: return null
        val (numeric, consumed) = prefix
        return NumberToken(value = numeric.toString(), numeric = numeric, consumed = consumed)
    }

    private fun clean(text: String): String = WordNumbers.normalize(text)
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private data class NumberToken(val value: String, val numeric: Int?, val consumed: Int)

    companion object {
        /** Rango inclusivo máximo que se expande, para no derivar cientos de valores de un error. */
        const val MAX_RANGE = 50

        private val PAGE_KEYWORD = Regex("\\bpaginas?\\b")
        private val EXERCISE_KEYWORD = Regex("\\bejercicios?\\b")
        private val RANGE_CONNECTOR = Regex("^(al?|hasta)\\s+")
        private val LEADING_FILLER = Regex("^(y|e|del|de|la|el|los|las|numero|nro|n)\\b")
        private val DIGIT_TOKEN = Regex("^\\d+[a-z]?")
    }
}

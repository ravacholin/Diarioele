package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.semantic.NumberKind.EXERCISE
import com.capo.diarioclase.processing.semantic.NumberKind.PAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del normalizador numérico de aula (Task Q1).
 *
 * Extrae, anclado por palabra clave ("página" / "ejercicio"), los números de página y de
 * ejercicio mencionados en la transcripción: palabras ("cuarenta y dos" → 42), dígitos,
 * rangos ("tres a cinco" → 3, 4, 5), listas ("3, 4 y 7") y alfanuméricos ("4b"). Sin una
 * palabra clave no extrae nada, así un teléfono o un año sueltos no se confunden con páginas.
 */
class SpanishNumberNormalizerTest {

    private val normalizer = SpanishNumberNormalizer()

    @Test
    fun `normalizes classroom numbers and ranges`() {
        assertEquals(listOf("42"), normalizer.values("página cuarenta y dos", PAGE))
        assertEquals(listOf("105"), normalizer.values("página ciento cinco", PAGE))
        assertEquals(listOf("3", "4", "5"), normalizer.values("ejercicios tres a cinco", EXERCISE))
        assertEquals(listOf("3", "4", "7"), normalizer.values("ejercicios 3, 4 y 7", EXERCISE))
        assertEquals(listOf("4b"), normalizer.values("ejercicio 4b", EXERCISE))
        assertTrue(normalizer.values("Mi teléfono termina en 2026", PAGE).isEmpty())
    }

    @Test
    fun `reads digit pages anchored by the keyword`() {
        assertEquals(listOf("12"), normalizer.values("vamos a la página 12", PAGE))
        assertEquals(listOf("12"), normalizer.values("Página 12", PAGE))
    }

    @Test
    fun `expands digit ranges`() {
        assertEquals(listOf("3", "4", "5"), normalizer.values("ejercicios 3 a 5", EXERCISE))
        assertEquals(listOf("3", "4", "5"), normalizer.values("ejercicios del 3 al 5", EXERCISE))
    }

    @Test
    fun `a page keyword is required`() {
        assertTrue(normalizer.values("hoy conversamos un rato", PAGE).isEmpty())
        assertTrue(normalizer.values("terminamos a las 105", PAGE).isEmpty())
    }

    @Test
    fun `keeps only the requested kind`() {
        // "ejercicio 3" no aporta una página aunque haya un número presente.
        assertTrue(normalizer.values("hicimos el ejercicio 3", PAGE).isEmpty())
        assertEquals(listOf("3"), normalizer.values("hicimos el ejercicio 3", EXERCISE))
    }

    @Test
    fun `deduplicates repeated numbers preserving order`() {
        assertEquals(listOf("3", "4"), normalizer.values("ejercicio 3 y 4 y 3", EXERCISE))
    }
}

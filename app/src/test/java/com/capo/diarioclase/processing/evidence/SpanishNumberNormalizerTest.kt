package com.capo.diarioclase.processing.evidence
import org.junit.Assert.assertEquals
import org.junit.Test
class SpanishNumberNormalizerTest {
 @Test fun `reads digits and spoken Spanish numbers`() {assertEquals(42,SpanishNumberNormalizer.readPrefix("cuarenta y dos")?.first);assertEquals(23,SpanishNumberNormalizer.readPrefix("veintitrés")?.first);assertEquals(105,SpanishNumberNormalizer.readPrefix("ciento cinco")?.first);assertEquals(17,SpanishNumberNormalizer.readPrefix("17")?.first)}
}

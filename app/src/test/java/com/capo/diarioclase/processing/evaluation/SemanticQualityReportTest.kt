package com.capo.diarioclase.processing.evaluation

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.semantic.DiaryField
import com.capo.diarioclase.processing.semantic.ProviderSemanticClaim
import com.capo.diarioclase.processing.semantic.PublicSpanId
import com.capo.diarioclase.processing.semantic.PublicTranscriptSpan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reporte de calidad semántica offline (Task Q7): métricas deterministas de la corrida. No usa
 * proveedores reales ni red; cada paquete entrega su respuesta "gold" en memoria.
 */
@RunWith(RobolectricTestRunner::class)
class SemanticQualityReportTest {

    private val runner = SemanticEvaluationRunner()

    @Test
    fun `summary reports perfect precision recall and f1 on a grounded run`() = runTest {
        val spans = listOf(
            span(2, 1, "Vamos a la página veinte.", 0),
            span(2, 2, "Hacemos el ejercicio dos.", 4_000),
        )
        val packet = EvalPacket(
            "pkt",
            spans,
            listOf(
                claim("B2-C1", "PAGE", "20", "PERFORMED", 0.95, listOf("B2-S1")),
                claim("B2-C2", "EXERCISE", "2", "PERFORMED", 0.92, listOf("B2-S2")),
            ),
        )
        val expectation = SemanticExpectation(
            required = setOf(
                ClaimExpectation(ClaimCategory.PAGE, "20", ClaimStatus.PERFORMED),
                ClaimExpectation(ClaimCategory.EXERCISE, "2", ClaimStatus.PERFORMED),
            ),
            expectedFields = mapOf(DiaryField.PAGES to "20 (2)"),
        )

        val summary = runner.summarize(listOf(packet), expectation)

        assertEquals(2, summary.expected)
        assertEquals(2, summary.truePositive)
        assertEquals(2, summary.statusCorrect)
        assertEquals(1, summary.exactFieldMatches)
        assertEquals(1.0, summary.precision()!!, 0.001)
        assertEquals(1.0, summary.recall()!!, 0.001)
        assertEquals(1.0, summary.f1()!!, 0.001)
        assertEquals(1, summary.providerAttempts["GEMINI"])
        assertEquals(0, summary.providerInvalidResponses["GEMINI"] ?: 0)
    }

    @Test
    fun `precision and recall are null without denominators`() = runTest {
        val spans = listOf(span(1, 1, "Hoy conversamos un rato.", 0))
        val packet = EvalPacket("pkt-empty", spans, emptyList())
        val summary = runner.summarize(listOf(packet), SemanticExpectation())
        assertEquals(0, summary.expected)
        assertEquals(0, summary.predicted)
        assertNull(summary.precision())
        assertNull(summary.recall())
        assertNull(summary.f1())
    }

    private fun span(block: Int, spanOrdinal: Int, text: String, startMs: Long) = PublicTranscriptSpan(
        publicId = PublicSpanId.of(block, spanOrdinal),
        blockOrdinal = block,
        audioSegmentOrdinal = spanOrdinal,
        spanOrdinal = spanOrdinal,
        startMs = startMs,
        endMs = startMs + 3_000,
        text = text,
        contextOnly = false,
    )

    private fun claim(
        claimKey: String,
        category: String,
        value: String,
        status: String,
        confidence: Double,
        evidenceSpanIds: List<String>,
    ) = ProviderSemanticClaim(claimKey, category, value, value, status, confidence, evidenceSpanIds, emptyList())
}

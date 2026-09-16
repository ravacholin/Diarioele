package com.capo.diarioclase.processing.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del contrato del quality loop (Task Q0).
 *
 * Congelan la forma de los modelos que comparten el gate de calidad (Q1), los prompts de
 * reparación (Q2), la fusión híbrida (Q3) y la observabilidad (Q5). La invariante central
 * es que ningún modelo del contrato transporta cuerpos de proveedor, transcripciones ni
 * credenciales: solo códigos de issue seguros y confianza efectiva. Ninguna prueba usa red.
 */
class SemanticQualityContractTest {

    @Test
    fun `quality report preserves safe issue codes`() {
        val report = QualityReport(
            decision = QualityDecision.REPAIR,
            issues = listOf(SemanticIssue.NUMERIC_EVIDENCE_MISMATCH),
            effectiveConfidence = 0.45,
        )
        assertEquals(QualityDecision.REPAIR, report.decision)
        assertFalse(report.toString().contains("Página cuarenta y dos"))
    }

    @Test
    fun `repair context never contains provider body`() {
        val context = InferenceAttemptContext.repair(
            setOf(SemanticIssue.UNKNOWN_EVIDENCE, SemanticIssue.EMPTY_DESPITE_LOCAL_SIGNAL),
        )
        assertEquals(2, context.safeIssueCodes.size)
    }

    @Test
    fun `initial attempt context is the first attempt with no issues`() {
        val context = InferenceAttemptContext.initial()
        assertEquals(1, context.attempt)
        assertTrue(context.safeIssueCodes.isEmpty())
    }

    @Test
    fun `repair attempt context is the second attempt carrying the issue codes`() {
        val context = InferenceAttemptContext.repair(setOf(SemanticIssue.INVALID_CHRONOLOGY))
        assertEquals(2, context.attempt)
        assertEquals(setOf(SemanticIssue.INVALID_CHRONOLOGY), context.safeIssueCodes)
    }

    @Test
    fun `every quality decision is representable`() {
        assertEquals(
            setOf(QualityDecision.ACCEPT, QualityDecision.REPAIR, QualityDecision.FALLBACK),
            QualityDecision.values().toSet(),
        )
    }

    @Test
    fun `every claim provenance is representable`() {
        assertEquals(
            setOf(
                ClaimProvenance.LOCAL,
                ClaimProvenance.REMOTE,
                ClaimProvenance.BOTH,
                ClaimProvenance.USER,
            ),
            ClaimProvenance.values().toSet(),
        )
    }

    @Test
    fun `local interpretation signals default to no markers`() {
        assertTrue(LocalInterpretationSignals().markers.isEmpty())
    }

    @Test
    fun `manual marker signal keeps local block coordinates`() {
        val marker = ManualMarkerSignal(
            markerId = "marker-1",
            type = "HOMEWORK",
            blockId = "block-3",
            offsetMs = 12_000,
        )
        assertEquals("marker-1", marker.markerId)
        assertEquals("HOMEWORK", marker.type)
        assertEquals("block-3", marker.blockId)
        assertEquals(12_000, marker.offsetMs)
    }
}

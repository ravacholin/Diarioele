package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.RawClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del gate de calidad semántica (Task Q1).
 *
 * El gate ancla los claims remotos en la evidencia local y calcula una confianza efectiva
 * determinista. Una discrepancia numérica nunca se compensa con confianza: siempre produce
 * REPAIR (o FALLBACK si ya hubo un reintento). No usa red.
 */
class SemanticQualityGateTest {

    private val gate = SemanticQualityGate()

    @Test
    fun `page 99 cannot be grounded by page 42`() {
        val report = gate.evaluate(packet("Página cuarenta y dos"), listOf(page("99")), listOf(page("42")))
        assertEquals(QualityDecision.REPAIR, report.decision)
        assertTrue(SemanticIssue.NUMERIC_EVIDENCE_MISMATCH in report.issues)
    }

    @Test
    fun `empty remote response with local page requires repair`() {
        val report = gate.evaluate(packet("Vamos a la página 12"), emptyList(), listOf(page("12")))
        assertEquals(QualityDecision.REPAIR, report.decision)
        assertTrue(SemanticIssue.EMPTY_DESPITE_LOCAL_SIGNAL in report.issues)
    }

    @Test
    fun `grounded remote page in agreement with local is accepted`() {
        val report = gate.evaluate(packet("Página cuarenta y dos"), listOf(page("42")), listOf(page("42")))
        assertEquals(QualityDecision.ACCEPT, report.decision)
        assertTrue(report.issues.isEmpty())
        assertTrue("confianza efectiva alta esperada", report.effectiveConfidence > 0.5)
    }

    @Test
    fun `numeric mismatch is never accepted even with maximum confidence`() {
        val report = gate.evaluate(
            packet("Página cuarenta y dos"),
            listOf(page("99", confidence = 0.99)),
            emptyList(),
        )
        assertNotEquals(QualityDecision.ACCEPT, report.decision)
        assertTrue(report.effectiveConfidence < 0.5)
    }

    @Test
    fun `empty remote without local signal has nothing to repair`() {
        val report = gate.evaluate(packet("Hoy conversamos un rato"), emptyList(), emptyList())
        assertEquals(QualityDecision.ACCEPT, report.decision)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `an unresolved issue after a repair falls back to local`() {
        val report = gate.evaluate(
            packet("Página cuarenta y dos"),
            listOf(page("99")),
            listOf(page("42")),
            repairAlreadyAttempted = true,
        )
        assertEquals(QualityDecision.FALLBACK, report.decision)
    }

    @Test
    fun `scoring exposes remote claims with effective confidence`() {
        val scored = gate.score(packet("Página cuarenta y dos"), listOf(page("42")), listOf(page("42")))
        assertEquals(1, scored.size)
        assertTrue(scored.single().effectiveConfidence > 0.5)
    }

    // --- Helpers -----------------------------------------------------------------------

    private fun page(value: String, confidence: Double = 0.9): RawClaim = RawClaim(
        id = "c-$value",
        category = ClaimCategory.PAGE,
        value = value,
        normalizedValue = value,
        status = ClaimStatus.PERFORMED,
        confidence = confidence,
        origin = ClaimOrigin.GEMINI,
        evidence = EvidenceRef(BlockId("B1"), 0, 1_000, "evidencia"),
    )

    private fun packet(vararg texts: String): InterpretationPacket {
        val spans = texts.mapIndexed { index, text ->
            PublicTranscriptSpan(
                publicId = "B1-S${index + 1}",
                blockOrdinal = 1,
                audioSegmentOrdinal = 1,
                spanOrdinal = index + 1,
                startMs = index * 1_000L,
                endMs = index * 1_000L + 1_000,
                text = text,
                contextOnly = false,
            )
        }
        val sources = spans.associate { it.publicId to "local-${it.publicId}" }
        return InterpretationPacket(
            InterpretationRequest("packet-1", "free-ele-v1", "claims-v1", spans),
            sources,
        )
    }
}

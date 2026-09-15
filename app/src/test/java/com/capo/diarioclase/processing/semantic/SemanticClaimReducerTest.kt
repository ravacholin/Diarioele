package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.RawClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticClaimReducerTest {

    private val reducer = SemanticClaimReducer()

    private fun raw(
        key: String,
        category: ClaimCategory,
        value: String,
        status: ClaimStatus = ClaimStatus.PERFORMED,
        supersedes: List<String> = emptyList(),
    ) = RawClaim(
        id = key,
        category = category,
        value = value,
        normalizedValue = value,
        status = status,
        confidence = 0.9,
        origin = ClaimOrigin.GEMINI,
        evidence = EvidenceRef(BlockId("B1"), 0, 1_000, "evidencia"),
        claimKey = key,
        evidences = listOf(EvidenceRef(BlockId("B1"), 0, 1_000, "evidencia")),
        supersedesClaimKeys = supersedes,
    )

    private fun active(claims: List<com.capo.diarioclase.processing.evidence.EvidenceClaim>, key: String) =
        claims.first { it.claimKey == key }.active

    @Test
    fun `repeated same page keeps only the last active`() {
        val result = reducer.reduce(
            listOf(
                raw("C1", ClaimCategory.PAGE, "42"),
                raw("C2", ClaimCategory.PAGE, "42"),
            ),
        )
        assertFalse(active(result, "C1"))
        assertTrue(active(result, "C2"))
    }

    @Test
    fun `superseded exercise becomes inactive and only the referenced one`() {
        val result = reducer.reduce(
            listOf(
                raw("C1", ClaimCategory.EXERCISE, "3"),
                raw("C2", ClaimCategory.EXERCISE, "4"),
                raw("C3", ClaimCategory.EXERCISE, "4", status = ClaimStatus.ASSIGNED, supersedes = listOf("C2")),
            ),
        )
        assertTrue("el 3 no fue tocado", active(result, "C1"))
        assertFalse("el 4 realizado quedó reemplazado", active(result, "C2"))
        assertTrue("el 4 como tarea queda activo", active(result, "C3"))
    }

    @Test
    fun `later cancellation supersedes the earlier claim`() {
        val result = reducer.reduce(
            listOf(
                raw("C1", ClaimCategory.EXERCISE, "5"),
                raw("C2", ClaimCategory.EXERCISE, "5", status = ClaimStatus.CANCELLED, supersedes = listOf("C1")),
            ),
        )
        assertFalse(active(result, "C1"))
        assertTrue(active(result, "C2"))
    }

    @Test
    fun `preserves entry order`() {
        val input = listOf(
            raw("C1", ClaimCategory.TOPIC, "perfecto"),
            raw("C2", ClaimCategory.PAGE, "42"),
            raw("C3", ClaimCategory.EXERCISE, "3"),
        )
        val result = reducer.reduce(input)
        assertEquals(listOf("C1", "C2", "C3"), result.map { it.claimKey })
    }

    @Test
    fun `duplicate across packets is deduplicated keeping last`() {
        val result = reducer.reduce(
            listOf(
                raw("C1", ClaimCategory.EXERCISE, "6"),
                raw("C2", ClaimCategory.EXERCISE, "6"),
            ),
        )
        assertFalse(active(result, "C1"))
        assertTrue(active(result, "C2"))
    }

    @Test
    fun `preserves namespaced claim metadata`() {
        val raw = raw("C1", ClaimCategory.PAGE, "42").copy(
            runId = "run-1",
            packetId = "packet-2",
            providerClaimKey = "provider-C1",
            declaredConfidence = 0.95,
            effectiveConfidence = 0.7,
            transcriptSpanIds = listOf("span-local-3"),
            claimOrdinal = 2,
        )

        val claim = reducer.reduce(listOf(raw)).single()

        assertEquals(raw.runId, claim.runId)
        assertEquals(raw.packetId, claim.packetId)
        assertEquals(raw.providerClaimKey, claim.providerClaimKey)
        assertEquals(raw.declaredConfidence, claim.declaredConfidence, 0.0)
        assertEquals(raw.effectiveConfidence, claim.effectiveConfidence, 0.0)
        assertEquals(raw.transcriptSpanIds, claim.transcriptSpanIds)
        assertEquals(raw.claimOrdinal, claim.claimOrdinal)
    }
}

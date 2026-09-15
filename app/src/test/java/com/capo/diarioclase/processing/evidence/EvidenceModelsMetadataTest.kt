package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.data.db.BlockId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceModelsMetadataTest {

    @Test
    fun `raw claim metadata defaults preserve source compatibility`() {
        val claim = rawClaim()

        assertNull(claim.runId)
        assertNull(claim.packetId)
        assertEquals(claim.claimKey, claim.providerClaimKey)
        assertEquals(claim.confidence, claim.declaredConfidence, 0.0)
        assertEquals(claim.confidence, claim.effectiveConfidence, 0.0)
        assertTrue(claim.transcriptSpanIds.isEmpty())
        assertEquals(0, claim.claimOrdinal)
    }

    @Test
    fun `evidence claim carries explicit run packet confidence and local span ids`() {
        val evidence = evidence()
        val claim = EvidenceClaim(
            id = "claim-global",
            category = ClaimCategory.PAGE,
            value = "42",
            normalizedValue = "42",
            status = ClaimStatus.PERFORMED,
            confidence = 0.7,
            origin = ClaimOrigin.GEMINI,
            evidence = evidence,
            runId = "run-1",
            packetId = "packet-2",
            providerClaimKey = "C1",
            declaredConfidence = 0.9,
            effectiveConfidence = 0.7,
            transcriptSpanIds = listOf("span-local-3", "span-local-4"),
            claimOrdinal = 3,
        )

        assertEquals("run-1", claim.runId)
        assertEquals("packet-2", claim.packetId)
        assertEquals("C1", claim.providerClaimKey)
        assertEquals(0.9, claim.declaredConfidence, 0.0)
        assertEquals(0.7, claim.effectiveConfidence, 0.0)
        assertEquals(listOf("span-local-3", "span-local-4"), claim.transcriptSpanIds)
        assertEquals(3, claim.claimOrdinal)
    }

    @Test
    fun `legacy reducer preserves namespaced metadata`() {
        val raw = rawClaim().copy(
            runId = "run-1",
            packetId = "packet-2",
            providerClaimKey = "provider-C1",
            declaredConfidence = 0.95,
            effectiveConfidence = 0.65,
            transcriptSpanIds = listOf("span-local-3"),
            claimOrdinal = 4,
        )

        val claim = ClaimReducer().reduce(listOf(raw)).single()

        assertEquals(raw.runId, claim.runId)
        assertEquals(raw.packetId, claim.packetId)
        assertEquals(raw.providerClaimKey, claim.providerClaimKey)
        assertEquals(raw.declaredConfidence, claim.declaredConfidence, 0.0)
        assertEquals(raw.effectiveConfidence, claim.effectiveConfidence, 0.0)
        assertEquals(raw.transcriptSpanIds, claim.transcriptSpanIds)
        assertEquals(raw.claimOrdinal, claim.claimOrdinal)
    }

    private fun rawClaim() = RawClaim(
        id = "claim-global",
        category = ClaimCategory.PAGE,
        value = "42",
        normalizedValue = "42",
        status = ClaimStatus.PERFORMED,
        confidence = 0.8,
        origin = ClaimOrigin.LOCAL_RULE,
        evidence = evidence(),
        claimKey = "C1",
    )

    private fun evidence() = EvidenceRef(
        blockId = BlockId("block-1"),
        startMs = 0,
        endMs = 1_000,
        excerpt = "página 42",
    )
}

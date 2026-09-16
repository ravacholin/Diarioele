package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.RawClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la fusión híbrida local + remota (Task Q3). No usa red.
 */
class HybridClaimMergerTest {

    private val merger = HybridClaimMerger()

    @Test
    fun `matching local and remote claims become both`() {
        val local = page("42", ClaimOrigin.LOCAL_RULE, startMs = 0, endMs = 1_000)
        val remote = page("42", ClaimOrigin.GEMINI, startMs = 0, endMs = 1_000)
        val result = merger.merge(listOf(local), listOf(remote))
        assertEquals(ClaimProvenance.BOTH, result.single().provenance)
        assertEquals(ClaimOrigin.GEMINI, result.single().origin) // base remota
    }

    @Test
    fun `remote numeric conflict cannot delete grounded local claim`() {
        val local = page("42", ClaimOrigin.LOCAL_RULE, startMs = 0, endMs = 1_000)
        val remote = page("99", ClaimOrigin.GEMINI, startMs = 1_000, endMs = 2_000)
        val result = merger.merge(listOf(local), listOf(remote))
        assertTrue(result.any { it.normalizedValue == "42" && it.active })
        assertTrue(result.any { it.normalizedValue == "99" && it.status == ClaimStatus.UNCERTAIN })
    }

    @Test
    fun `weaker duplicate does not replace stronger claim`() {
        val strong = page("42", ClaimOrigin.LOCAL_RULE, startMs = 0, endMs = 1_000, effective = 0.95)
        val weak = page("42", ClaimOrigin.GEMINI, startMs = 0, endMs = 1_000, effective = 0.5)
        assertEquals(
            0.95,
            merger.merge(listOf(strong), listOf(weak)).single { it.active }.effectiveConfidence,
            0.001,
        )
    }

    @Test
    fun `a later correction from remote refines the local status`() {
        val local = exercise("4", ClaimStatus.PERFORMED, ClaimOrigin.LOCAL_RULE, startMs = 0, endMs = 1_000)
        val remote = exercise("4", ClaimStatus.ASSIGNED, ClaimOrigin.GEMINI, startMs = 0, endMs = 1_000)
        val result = merger.merge(listOf(local), listOf(remote))
        assertEquals(1, result.size)
        assertEquals(ClaimStatus.ASSIGNED, result.single().status)
        assertEquals(ClaimProvenance.BOTH, result.single().provenance)
    }

    @Test
    fun `a local claim already interpreted by remote is dropped`() {
        // La tarea local "queda para casa" (span 1000-2000) ya fue citada por el ejercicio remoto.
        val localHomework = homework("queda para casa", ClaimOrigin.LOCAL_RULE, startMs = 1_000, endMs = 2_000)
        val remote = exercise(
            "4", ClaimStatus.ASSIGNED, ClaimOrigin.GEMINI,
            evidences = listOf(evidence(0, 1_000), evidence(1_000, 2_000)),
        )
        val result = merger.merge(listOf(localHomework), listOf(remote))
        assertTrue(result.none { it.category == ClaimCategory.HOMEWORK && it.value == "queda para casa" })
        assertEquals(1, result.size)
    }

    @Test
    fun `a local gap filler on uncited spans is kept as local`() {
        val localTopic = RawClaim(
            id = "l-topic",
            category = ClaimCategory.TOPIC,
            value = "saludos",
            normalizedValue = "saludos",
            status = ClaimStatus.PERFORMED,
            confidence = 0.7,
            origin = ClaimOrigin.LOCAL_RULE,
            evidence = evidence(5_000, 6_000),
            evidences = listOf(evidence(5_000, 6_000)),
        )
        val remote = page("42", ClaimOrigin.GEMINI, startMs = 0, endMs = 1_000)
        val result = merger.merge(listOf(localTopic), listOf(remote))
        val topic = result.single { it.category == ClaimCategory.TOPIC }
        assertEquals(ClaimProvenance.LOCAL, topic.provenance)
        assertTrue(topic.active)
    }

    @Test
    fun `a manual marker homework candidate survives the merge as local`() {
        val marker = RawClaim(
            id = "marker-m1",
            category = ClaimCategory.HOMEWORK,
            value = "tarea marcada",
            normalizedValue = "tarea marcada",
            status = ClaimStatus.ASSIGNED,
            confidence = 0.6,
            origin = ClaimOrigin.MANUAL_MARKER,
            evidence = evidence(5_000, 6_000),
            evidences = listOf(evidence(5_000, 6_000)),
        )
        val result = merger.merge(listOf(marker), emptyList())
        val homework = result.single()
        assertEquals(ClaimCategory.HOMEWORK, homework.category)
        assertEquals(ClaimProvenance.LOCAL, homework.provenance)
        assertTrue(homework.active)
    }

    // --- Helpers -----------------------------------------------------------------------

    private fun evidence(startMs: Long, endMs: Long): EvidenceRef =
        EvidenceRef(BlockId("B1"), startMs, endMs, "span", blockOrdinal = 1, spanOrdinal = 1)

    private fun page(
        value: String,
        origin: ClaimOrigin,
        startMs: Long,
        endMs: Long,
        effective: Double = 0.9,
    ): RawClaim = RawClaim(
        id = "$origin-page-$value",
        category = ClaimCategory.PAGE,
        value = value,
        normalizedValue = value,
        status = ClaimStatus.PERFORMED,
        confidence = 0.9,
        origin = origin,
        evidence = evidence(startMs, endMs),
        evidences = listOf(evidence(startMs, endMs)),
        effectiveConfidence = effective,
    )

    private fun exercise(
        value: String,
        status: ClaimStatus,
        origin: ClaimOrigin,
        startMs: Long = 0,
        endMs: Long = 1_000,
        evidences: List<EvidenceRef> = listOf(evidence(startMs, endMs)),
    ): RawClaim = RawClaim(
        id = "$origin-ex-$value-$status",
        category = ClaimCategory.EXERCISE,
        value = value,
        normalizedValue = value,
        status = status,
        confidence = 0.9,
        origin = origin,
        evidence = evidences.first(),
        evidences = evidences,
    )

    private fun homework(
        value: String,
        origin: ClaimOrigin,
        startMs: Long,
        endMs: Long,
    ): RawClaim = RawClaim(
        id = "$origin-hw-$value",
        category = ClaimCategory.HOMEWORK,
        value = value,
        normalizedValue = value,
        status = ClaimStatus.ASSIGNED,
        confidence = 0.8,
        origin = origin,
        evidence = evidence(startMs, endMs),
        evidences = listOf(evidence(startMs, endMs)),
    )
}

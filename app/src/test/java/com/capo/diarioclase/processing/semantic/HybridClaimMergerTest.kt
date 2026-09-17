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
        // La página remota está anclada en su propia evidencia ("noventa y nueve"), pero
        // contradice a la página local con evidencia: pasa a UNCERTAIN, no se borra la local.
        val remote = remotePage(
            value = "99", excerpt = "Vamos a la página noventa y nueve", quote = "",
            startMs = 1_000, endMs = 2_000,
        )
        val result = merger.merge(listOf(local), listOf(remote))
        assertTrue(result.any { it.normalizedValue == "42" && it.active })
        assertTrue(result.any { it.normalizedValue == "99" && it.status == ClaimStatus.UNCERTAIN })
    }

    @Test
    fun `two explicit pages in one excerpt are not treated as a contradiction`() {
        val excerpt = "página 1 ejercicio 2 página 2 ejercicio 3 y 4"
        val local = page("1", ClaimOrigin.LOCAL_RULE, startMs = 0, endMs = 1_000)
        val remoteFirst = remotePage(value = "1", excerpt = excerpt, quote = "página 1")
        val remoteSecond = remotePage(value = "2", excerpt = excerpt, quote = "página 2")

        val result = merger.merge(listOf(local), listOf(remoteFirst, remoteSecond))

        assertEquals(ClaimStatus.PERFORMED, result.single { it.normalizedValue == "2" }.status)
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
            evidences = listOf(evidence(0, 1_000, "Hacemos el ejercicio cuatro"), evidence(1_000, 2_000)),
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

    @Test
    fun `remote page number grounded by token in the evidence survives as is`() {
        val remote = remotePage(
            value = "42",
            excerpt = "Vamos a la página cuarenta y dos",
            quote = "",
        )
        val result = merger.merge(emptyList(), listOf(remote))
        val page = result.single()
        assertEquals(ClaimStatus.PERFORMED, page.status)
        assertEquals(ClaimProvenance.REMOTE, page.provenance)
    }

    @Test
    fun `remote page number without token but with a real keyword quote is rescued as uncertain`() {
        // Whisper transcribió mal el número ("dolce"), pero la IA infirió 12 y citó el fragmento.
        val remote = remotePage(
            value = "12",
            excerpt = "Vamos a la página dolce",
            quote = "página dolce",
        )
        val result = merger.merge(emptyList(), listOf(remote))
        val page = result.single()
        assertEquals(ClaimStatus.UNCERTAIN, page.status)
    }

    @Test
    fun `remote page number without token and without a valid quote is dropped`() {
        val invented = remotePage(
            value = "500",
            excerpt = "Hoy conversamos sobre comida",
            quote = "",
        )
        assertTrue(merger.merge(emptyList(), listOf(invented)).none { it.category == ClaimCategory.PAGE })
    }

    @Test
    fun `an unrelated quote does not rescue an invented page number`() {
        val smuggled = remotePage(
            value = "500",
            excerpt = "Hola, cómo están",
            quote = "Hola, cómo están",
        )
        assertTrue(merger.merge(emptyList(), listOf(smuggled)).none { it.category == ClaimCategory.PAGE })
    }

    @Test
    fun `a remote number that agrees with a local candidate is never dropped`() {
        // Sin token en el excerpt remoto, pero lo local lo respalda: se funde como BOTH.
        val local = page("12", ClaimOrigin.LOCAL_RULE, startMs = 0, endMs = 1_000)
        val remote = remotePage(value = "12", excerpt = "a la página que dije", quote = "")
        val result = merger.merge(listOf(local), listOf(remote))
        assertEquals(ClaimProvenance.BOTH, result.single().provenance)
        assertEquals(ClaimStatus.PERFORMED, result.single().status)
    }

    // --- Helpers -----------------------------------------------------------------------

    private fun evidence(startMs: Long, endMs: Long, excerpt: String = "span"): EvidenceRef =
        EvidenceRef(BlockId("B1"), startMs, endMs, excerpt, blockOrdinal = 1, spanOrdinal = 1)

    private fun remotePage(
        value: String,
        excerpt: String,
        quote: String,
        startMs: Long = 0,
        endMs: Long = 1_000,
    ): RawClaim {
        val ref = EvidenceRef(BlockId("B1"), startMs, endMs, excerpt, blockOrdinal = 1, spanOrdinal = 1)
        return RawClaim(
            id = "gemini-page-$value",
            category = ClaimCategory.PAGE,
            value = value,
            normalizedValue = value,
            status = ClaimStatus.PERFORMED,
            confidence = 0.9,
            origin = ClaimOrigin.GEMINI,
            evidence = ref,
            evidences = listOf(ref),
            evidenceQuote = quote.ifBlank { null },
        )
    }

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

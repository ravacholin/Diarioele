package com.capo.diarioclase.processing.evaluation

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.DraftFieldRevision
import com.capo.diarioclase.data.db.ReviewAction
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.semantic.ClaimProvenance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de privacidad del corpus local (Task Q6). El ejemplo nunca contiene audio, id de
 * sesión ni fechas, y conserva solo los spans citados y un vecino a cada lado. No usa red.
 */
class LocalExampleRepositoryTest {

    private val session = "sess-123"
    private val codec = JsonlExampleCodec()

    private fun source() = object : LocalExampleSource {
        override suspend fun orderedSpans(sessionId: String) = listOf(
            ExampleSourceSpan("t0", "Saludos iniciales"),
            ExampleSourceSpan("t1", "grabado en /audio/seg.wav el 2026-09-16, sesión sess-123"),
            ExampleSourceSpan("t2", "Vamos a la página cuarenta"),
            ExampleSourceSpan("t3", "Hacemos el ejercicio dos"),
            ExampleSourceSpan("t4", "Comentario suelto"),
            ExampleSourceSpan("t5", "Otro comentario"),
            ExampleSourceSpan("t6", "Cierre"),
        )
        override suspend fun claims(sessionId: String) = listOf(
            claim("c1", ClaimCategory.PAGE, "40", listOf("t1")),
            claim("c2", ClaimCategory.EXERCISE, "2", listOf("t4")),
        )
        override suspend fun revisions(sessionId: String) = listOf(
            DraftFieldRevision("r1", session, "PAGES", "40", "41", "USER", ReviewAction.CORRECT, "c1", 1),
        )
        override suspend fun finalFields(sessionId: String) = mapOf(
            "TOPICS" to "clase del 2026-09-16", "PAGES" to "41",
        )
        override fun pipelineVersions() = PipelineVersions("0.6.0", "w1", "p1", "s1", "v1", "quality-score-v1")
    }

    private val repository = LocalExampleRepository(source())

    @Test
    fun `example excludes audio session and date`() = runTest {
        val json = codec.encode(repository.buildExample(session))
        assertFalse(json.contains(".wav"))
        assertFalse(json.contains(session))
        assertFalse(json.contains("2026-"))
    }

    @Test
    fun `only cited spans and one neighbor are kept`() = runTest {
        val example = repository.buildExample(session)
        val citedCount = 2 // t1 y t4
        assertTrue(example.spans.size <= citedCount + citedCount * 2)
    }

    @Test
    fun `claims keep their provenance and evidence maps to kept spans`() = runTest {
        val example = repository.buildExample(session)
        val page = example.automaticClaims.single { it.category == "PAGE" }
        assertEquals(ClaimProvenance.BOTH.name, page.provenance)
        assertTrue(page.evidencePublicIds.all { it.startsWith("S") })
    }

    private fun claim(id: String, category: ClaimCategory, normalized: String, spanIds: List<String>) =
        EvidenceClaim(
            id = id,
            category = category,
            value = normalized,
            normalizedValue = normalized,
            status = ClaimStatus.PERFORMED,
            confidence = 0.9,
            origin = ClaimOrigin.GEMINI,
            evidence = EvidenceRef(BlockId("b1"), 0, 1_000, "x"),
            transcriptSpanIds = spanIds,
            provenance = ClaimProvenance.BOTH,
        )
}

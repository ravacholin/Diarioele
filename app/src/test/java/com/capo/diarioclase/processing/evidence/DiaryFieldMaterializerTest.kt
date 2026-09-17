package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.data.db.BlockId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryFieldMaterializerTest {

    private val materializer = DiaryFieldMaterializer(
        projector = InterpretationProjector(),
        composer = PagesAndExercisesComposer(),
    )

    @Test
    fun `assigned exercise goes to homework only`() {
        val draft = materializer.materialize(
            sessionId = "s",
            mode = InterpretationMode.CONSERVATIVE,
            claims = listOf(
                claim(
                    id = "e1",
                    category = ClaimCategory.EXERCISE,
                    status = ClaimStatus.ASSIGNED,
                    value = "4",
                ),
            ),
        )

        assertEquals("4", draft.homework)
        assertFalse(draft.pages.contains("4"))
        assertEquals("", draft.exercises)
    }

    @Test
    fun `performed page and exercise remain combined in pages`() {
        val draft = materializer.materialize(
            sessionId = "s",
            mode = InterpretationMode.BALANCED,
            claims = listOf(
                claim("p1", ClaimCategory.PAGE, ClaimStatus.PERFORMED, "42"),
                claim("e1", ClaimCategory.EXERCISE, ClaimStatus.PERFORMED, "3"),
            ),
        )

        assertEquals("42 (3)", draft.pages)
        assertEquals("", draft.exercises)
        assertTrue(draft.homework.isEmpty())
    }

    @Test
    fun `uncertain claim stays in confirmation and out of fields`() {
        val draft = materializer.materialize(
            sessionId = "s",
            mode = InterpretationMode.EXHAUSTIVE,
            claims = listOf(
                claim("h1", ClaimCategory.HOMEWORK, ClaimStatus.UNCERTAIN, "leer capítulo 2"),
            ),
        )

        assertEquals("", draft.homework)
        assertEquals(listOf("h1"), draft.confirm.map { it.id })
    }

    @Test
    fun `session summary is carried into the draft`() {
        val draft = materializer.materialize(
            sessionId = "s",
            mode = InterpretationMode.BALANCED,
            claims = listOf(claim("p1", ClaimCategory.PAGE, ClaimStatus.PERFORMED, "42")),
            summary = "Trabajamos vocabulario y la página 42.",
        )
        assertEquals("Trabajamos vocabulario y la página 42.", draft.summary)
    }

    @Test
    fun `absent summary defaults to empty`() {
        val draft = materializer.materialize(
            sessionId = "s",
            mode = InterpretationMode.BALANCED,
            claims = emptyList(),
        )
        assertEquals("", draft.summary)
    }

    private fun claim(
        id: String,
        category: ClaimCategory,
        status: ClaimStatus,
        value: String,
        block: String = "block-1",
        startMs: Long = 0,
        confidence: Double = 1.0,
    ) = EvidenceClaim(
        id = id,
        category = category,
        value = value,
        normalizedValue = value,
        status = status,
        confidence = confidence,
        origin = ClaimOrigin.GEMINI,
        evidence = EvidenceRef(BlockId(block), startMs, startMs + 1, value),
    )
}

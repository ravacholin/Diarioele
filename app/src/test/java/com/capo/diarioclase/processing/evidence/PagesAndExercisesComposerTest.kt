package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.data.db.BlockId
import org.junit.Assert.assertEquals
import org.junit.Test

class PagesAndExercisesComposerTest {

    private val composer = PagesAndExercisesComposer()

    @Test
    fun `new block resets the active page`() {
        val result = composer.compose(
            listOf(
                claim("p1", ClaimCategory.PAGE, "42", block = "block-1"),
                claim("e1", ClaimCategory.EXERCISE, "3", block = "block-1"),
                claim("e2", ClaimCategory.EXERCISE, "4", block = "block-2"),
            ),
        )

        assertEquals("42 (3)\n4", result)
    }

    @Test
    fun `cancelled page never provides exercise context`() {
        val result = composer.compose(
            listOf(
                claim("p1", ClaimCategory.PAGE, "99", status = ClaimStatus.CANCELLED),
                claim("e1", ClaimCategory.EXERCISE, "5"),
            ),
        )

        assertEquals("5", result)
    }

    @Test
    fun `assigned exercise is excluded from class pages`() {
        val result = composer.compose(
            listOf(
                claim("p1", ClaimCategory.PAGE, "20"),
                claim("e1", ClaimCategory.EXERCISE, "2", status = ClaimStatus.ASSIGNED),
            ),
        )

        assertEquals("20", result)
    }

    @Test
    fun `page context and exercise order follow evidence order`() {
        val result = composer.compose(
            listOf(
                claim("p1", ClaimCategory.PAGE, "14", startMs = 0),
                claim("e1", ClaimCategory.EXERCISE, "3", startMs = 1),
                claim("e2", ClaimCategory.EXERCISE, "a", startMs = 2),
                claim("p2", ClaimCategory.PAGE, "22", startMs = 3),
                claim("e3", ClaimCategory.EXERCISE, "1", startMs = 4),
            ),
        )

        assertEquals("14 (3, a)\n22 (1)", result)
    }

    private fun claim(
        id: String,
        category: ClaimCategory,
        value: String,
        block: String = "block-1",
        startMs: Long = 0,
        status: ClaimStatus = ClaimStatus.PERFORMED,
    ) = EvidenceClaim(
        id = id,
        category = category,
        value = value,
        normalizedValue = value,
        status = status,
        confidence = 1.0,
        origin = ClaimOrigin.GEMINI,
        evidence = EvidenceRef(BlockId(block), startMs, startMs + 1, value),
    )
}

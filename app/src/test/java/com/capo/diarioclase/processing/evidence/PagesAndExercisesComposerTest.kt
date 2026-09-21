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

        assertEquals("Página 42: ejercicio 3\nEjercicio sin página: 4", result)
    }

    @Test
    fun `cancelled page never provides exercise context`() {
        val result = composer.compose(
            listOf(
                claim("p1", ClaimCategory.PAGE, "99", status = ClaimStatus.CANCELLED),
                claim("e1", ClaimCategory.EXERCISE, "5"),
            ),
        )

        assertEquals("Ejercicio sin página: 5", result)
    }

    @Test
    fun `assigned exercise is excluded from class pages`() {
        val result = composer.compose(
            listOf(
                claim("p1", ClaimCategory.PAGE, "20"),
                claim("e1", ClaimCategory.EXERCISE, "2", status = ClaimStatus.ASSIGNED),
            ),
        )

        assertEquals("Página 20", result)
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

        assertEquals("Página 14: ejercicios 3 y a\nPágina 22: ejercicio 1", result)
    }

    @Test
    fun `provider claim order cannot attach an earlier exercise to a later page`() {
        val result = composer.compose(
            listOf(
                claim("p1", ClaimCategory.PAGE, "22", segmentOrdinal = 1, spanOrdinal = 3),
                claim("e1", ClaimCategory.EXERCISE, "3", segmentOrdinal = 1, spanOrdinal = 1),
            ),
        )

        assertEquals("Ejercicio sin página: 3\nPágina 22", result)
    }

    @Test
    fun `explicit block ordinals prevent interleaved provider output`() {
        val result = composer.compose(
            listOf(
                claim(
                    "p2", ClaimCategory.PAGE, "22", block = "block-2",
                    blockOrdinal = 2, segmentOrdinal = 1, spanOrdinal = 1,
                ),
                claim(
                    "p1", ClaimCategory.PAGE, "14", block = "block-1",
                    blockOrdinal = 1, segmentOrdinal = 1, spanOrdinal = 1,
                ),
                claim(
                    "e1",
                    ClaimCategory.EXERCISE,
                    "3",
                    block = "block-1",
                    blockOrdinal = 1,
                    segmentOrdinal = 1,
                    spanOrdinal = 2,
                ),
            ),
        )

        assertEquals("Página 14: ejercicio 3\nPágina 22", result)
    }

    @Test
    fun `groups the screenshot exercises under their pages even when claims arrive by category`() {
        val firstExcerpt =
            "Bueno, vamos a hacer la página 1, el ejercicio 2, la página 2, el ejercicio 3 y 4"
        val result = composer.compose(
            listOf(
                claim("e2", ClaimCategory.EXERCISE, "2", excerpt = firstExcerpt),
                claim("e3", ClaimCategory.EXERCISE, "3", excerpt = firstExcerpt),
                claim("e4", ClaimCategory.EXERCISE, "4", excerpt = firstExcerpt),
                claim("p1", ClaimCategory.PAGE, "1", excerpt = firstExcerpt),
                claim("p2", ClaimCategory.PAGE, "2", excerpt = firstExcerpt),
                claim(
                    "e8", ClaimCategory.EXERCISE, "8",
                    startMs = 2, excerpt = "el ejercicio 8 en la página 7",
                ),
                claim(
                    "p7", ClaimCategory.PAGE, "7",
                    startMs = 2, excerpt = "el ejercicio 8 en la página 7",
                ),
            ),
        )

        assertEquals(
            "Página 1: ejercicio 2\nPágina 2: ejercicios 3 y 4\nPágina 7: ejercicio 8",
            result,
        )
    }

    @Test
    fun `explicit relation never falls back to a different accepted page`() {
        val result = composer.compose(
            listOf(
                claim("p1", ClaimCategory.PAGE, "1", excerpt = "página 1"),
                claim("e8", ClaimCategory.EXERCISE, "8 (p. 7)", excerpt = "ejercicio 8 en la página 7"),
            ),
        )

        assertEquals("Página 1\nEjercicio sin página: 8", result)
    }

    private fun claim(
        id: String,
        category: ClaimCategory,
        value: String,
        block: String = "block-1",
        startMs: Long = 0,
        status: ClaimStatus = ClaimStatus.PERFORMED,
        blockOrdinal: Int? = null,
        segmentOrdinal: Int? = null,
        spanOrdinal: Int? = null,
        excerpt: String = value,
    ) = EvidenceClaim(
        id = id,
        category = category,
        value = value,
        normalizedValue = value,
        status = status,
        confidence = 1.0,
        origin = ClaimOrigin.GEMINI,
        evidence = EvidenceRef(
            blockId = BlockId(block),
            startMs = startMs,
            endMs = startMs + 1,
            excerpt = excerpt,
            blockOrdinal = blockOrdinal,
            audioSegmentOrdinal = segmentOrdinal,
            spanOrdinal = spanOrdinal,
        ),
    )
}

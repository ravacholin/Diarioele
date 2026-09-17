package com.capo.diarioclase.diary

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrativeReportComposerTest {

    private val composer = NarrativeReportComposer()

    @Test fun `explains date topics pages exercises and homework in prose`() {
        val report = composer.compose(
            pedagogicalDate = "2026-09-14",
            accepted = listOf(
                claim("t1", ClaimCategory.TOPIC, "el pretérito perfecto", startMs = 0),
                claim("p1", ClaimCategory.PAGE, "14", startMs = 1),
                claim("e1", ClaimCategory.EXERCISE, "1", startMs = 2),
                claim("e2", ClaimCategory.EXERCISE, "2", startMs = 3),
                claim("h1", ClaimCategory.HOMEWORK, "la página 19, ejercicios del 1 al 8", status = ClaimStatus.ASSIGNED, startMs = 4),
            ),
        )

        assertEquals(
            "Clase del 14/09/2026.\n\n" +
                "Temas: el pretérito perfecto.\n\n" +
                "En clase se trabajó en la página 14 (ejercicios 1, 2).\n\n" +
                "Tarea: la página 19, ejercicios del 1 al 8.",
            report,
        )
    }

    @Test fun `single exercise uses singular and multiple pages join naturally`() {
        val report = composer.compose(
            pedagogicalDate = null,
            accepted = listOf(
                claim("p1", ClaimCategory.PAGE, "14", block = "block-1", startMs = 0),
                claim("e1", ClaimCategory.EXERCISE, "3", block = "block-1", startMs = 1),
                claim("p2", ClaimCategory.PAGE, "22", block = "block-2", startMs = 2),
            ),
        )

        assertEquals("En clase se trabajó en la página 14 (ejercicio 3) y la página 22.", report)
    }

    @Test fun `empty interpretation yields a friendly fallback`() {
        val report = composer.compose(pedagogicalDate = "2026-09-14", accepted = emptyList())

        assertTrue(report.contains("Todavía no hay datos"))
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
        evidence = EvidenceRef(
            blockId = BlockId(block),
            startMs = startMs,
            endMs = startMs + 1,
            excerpt = value,
        ),
    )
}

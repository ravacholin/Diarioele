package com.capo.diarioclase.diary

import com.capo.diarioclase.data.db.CerLevel
import com.capo.diarioclase.data.db.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiaryClipboardFormatterTest {
    @Test fun `clipboard prefers permanent editorial report`() {
        val text = DiaryClipboardFormatter().format(
            entry(
                topics = "legacy que no debe copiarse",
                reportSummary = "Se trabajó el contraste de pasados.",
                reportMaterial = "Página 42, ejercicios 3 y 4.",
                reportHomework = "Terminar el ejercicio 5.",
            ),
        )

        assertEquals(
            "11/09/2026\n\nRESUMEN\nSe trabajó el contraste de pasados.\n\n" +
                "MATERIAL TRABAJADO\nPágina 42, ejercicios 3 y 4.\n\n" +
                "TAREA\nTerminar el ejercicio 5.",
            text,
        )
        assertFalse(text.contains("legacy que no debe copiarse"))
    }
    @Test fun `draft formatter includes current fields and omits empty headings`() {
        val text = DiaryClipboardFormatter().formatDraft("2026-09-11", "Pasados", "", "", "", "Ejercicio 6")
        assertEquals("11/09/2026\n\nTemas: Pasados\nTarea: Ejercicio 6", text)
    }
    @Test fun `formatter omits empty fields and metadata`() {
        val text = DiaryClipboardFormatter().format(entry(topics = "Pasados", pages = "", homework = "Ejercicio 6"))

        assertEquals("11/09/2026\n\nTemas: Pasados\nTarea: Ejercicio 6", text)
        assertFalse(text.contains("Páginas:"))
        assertFalse(text.contains("confianza", ignoreCase = true))
    }
}

private fun entry(
    topics: String = "",
    activities: String = "",
    pages: String = "",
    completedExercises: String = "",
    homework: String = "",
    reportSummary: String = "",
    reportMaterial: String = "",
    reportHomework: String = "",
) = DiaryEntry(
    id = "entry-1",
    sessionId = SessionId("session-1"),
    pedagogicalDate = "2026-09-11",
    level = CerLevel.B2,
    topics = topics,
    activities = activities,
    pages = pages,
    completedExercises = completedExercises,
    homework = homework,
    approvedAtEpochMs = 1_000,
    updatedAtEpochMs = 1_100,
    temporariesDeleted = false,
    reportSummary = reportSummary,
    reportMaterial = reportMaterial,
    reportHomework = reportHomework,
)

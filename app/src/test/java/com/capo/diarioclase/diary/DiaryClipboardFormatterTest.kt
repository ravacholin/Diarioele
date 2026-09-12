package com.capo.diarioclase.diary

import com.capo.diarioclase.data.db.CerLevel
import com.capo.diarioclase.data.db.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiaryClipboardFormatterTest {
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
)

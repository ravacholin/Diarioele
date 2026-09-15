package com.capo.diarioclase.diary

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class DiaryClipboardFormatter {
    fun format(entry: DiaryEntry): String = formatDraft(entry.pedagogicalDate, entry.topics, entry.activities, entry.pages, entry.completedExercises, entry.homework)

    fun formatDraft(pedagogicalDate: String?, topics: String, activities: String, pages: String, exercises: String, homework: String): String {
        val fields = listOf(
            "Temas" to topics,
            "Actividades realizadas" to activities,
            "Páginas y ejercicios" to pages,
            "Ejercicios hechos" to exercises,
            "Tarea" to homework,
        ).filter { (_, value) -> value.isNotBlank() }
            .joinToString("\n") { (label, value) -> "$label: $value" }
        val date = pedagogicalDate?.let { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)) }
        return listOfNotNull(date, fields.takeIf { it.isNotEmpty() }).joinToString("\n\n")
    }
}

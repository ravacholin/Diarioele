package com.capo.diarioclase.diary

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class DiaryClipboardFormatter {
    fun format(entry: DiaryEntry): String =
        if (entry.reportSummary.isNotBlank() || entry.reportMaterial.isNotBlank() || entry.reportHomework.isNotBlank()) {
            formatEditorial(entry.pedagogicalDate, entry.reportSummary, entry.reportMaterial, entry.reportHomework)
        } else {
            formatDraft(entry.pedagogicalDate, entry.topics, entry.activities, entry.pages, entry.completedExercises, entry.homework)
        }

    fun formatEditorial(
        pedagogicalDate: String?,
        summary: String,
        material: String,
        homework: String,
    ): String {
        val sections = listOf(
            "RESUMEN" to summary,
            "MATERIAL TRABAJADO" to material,
            "TAREA" to homework,
        ).filter { (_, value) -> value.isNotBlank() }
            .joinToString("\n\n") { (label, value) -> "$label\n$value" }
        val date = pedagogicalDate?.let {
            LocalDate.parse(it).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT))
        }
        return listOfNotNull(date, sections.takeIf { it.isNotEmpty() }).joinToString("\n\n")
    }

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

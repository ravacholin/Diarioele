package com.capo.diarioclase.diary

import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.PagesAndExercisesComposer
import com.capo.diarioclase.processing.semantic.DiaryField
import com.capo.diarioclase.processing.semantic.FieldTarget
import com.capo.diarioclase.processing.semantic.StatusFieldPolicy
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Arma un informe en prosa a partir de la interpretación ya aceptada (los claims que el modelo
 * extrajo y que superaron el umbral del modo). No llama a la red ni reprocesa nada: reordena y
 * redacta lo que la interpretación ya entendió para que el docente lo lea de corrido y lo copie
 * de un toque. Reemplaza la lectura fragmentada de los cinco campos por un texto explicado.
 */
class NarrativeReportComposer(
    private val pagesComposer: PagesAndExercisesComposer = PagesAndExercisesComposer(),
) {
    fun compose(pedagogicalDate: String?, accepted: List<EvidenceClaim>): String {
        val byField = accepted.groupBy { fieldOf(it) }
        val topics = values(byField[DiaryField.TOPICS])
        val activities = values(byField[DiaryField.ACTIVITIES])
        val pages = pagesProse(pagesComposer.composeLines(accepted))
        val homework = values(byField[DiaryField.HOMEWORK])

        val sentences = buildList {
            dateLine(pedagogicalDate)?.let { add(it) }
            if (topics.isNotBlank()) add("Temas: $topics.")
            if (activities.isNotBlank()) add("Actividades: $activities.")
            if (pages.isNotBlank()) add("En clase se trabajó en $pages.")
            if (homework.isNotBlank()) add("Tarea: $homework.")
        }
        return sentences.joinToString("\n\n").ifBlank { EMPTY_REPORT }
    }

    private fun fieldOf(claim: EvidenceClaim): DiaryField? =
        (StatusFieldPolicy.target(claim.category, claim.status) as? FieldTarget.Field)?.field

    private fun values(claims: List<EvidenceClaim>?): String =
        claims.orEmpty().map { it.value.trim() }.filter { it.isNotEmpty() }.joinToString("; ")

    private fun pagesProse(lines: List<PagesAndExercisesComposer.ComposedLine>): String {
        val parts = lines.mapNotNull { line ->
            when {
                line.page != null && line.exercises.isEmpty() -> "la página ${line.page}"
                line.page != null -> "la página ${line.page} (${exerciseWord(line.exercises.size)} ${line.exercises.joinToString(", ")})"
                line.exercises.isNotEmpty() -> "${exerciseWord(line.exercises.size)} ${line.exercises.joinToString(", ")}"
                else -> null
            }
        }
        return joinNatural(parts)
    }

    private fun exerciseWord(count: Int): String = if (count == 1) "ejercicio" else "ejercicios"

    private fun joinNatural(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts.first()
        else -> parts.dropLast(1).joinToString(", ") + " y " + parts.last()
    }

    private fun dateLine(pedagogicalDate: String?): String? {
        val date = pedagogicalDate
            ?.let { runCatching { LocalDate.parse(it).format(DATE_FORMAT) }.getOrNull() }
            ?: return null
        return "Clase del $date."
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
        const val EMPTY_REPORT = "Todavía no hay datos confirmados para esta clase."
    }
}

package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.processing.semantic.DiaryField
import com.capo.diarioclase.processing.semantic.FieldTarget
import com.capo.diarioclase.processing.semantic.NumberKind
import com.capo.diarioclase.processing.semantic.SpanishNumberNormalizer
import com.capo.diarioclase.processing.semantic.StatusFieldPolicy

/** Produces a readable page-to-exercise list from accepted evidence claims. */
class PagesAndExercisesComposer(
    private val numbers: SpanishNumberNormalizer = SpanishNumberNormalizer(),
) {
    private sealed interface Line { fun render(): String }

    private data class PageLine(
        val page: String,
        val exercises: MutableList<String> = mutableListOf(),
        var appended: Boolean = false,
    ) : Line {
        override fun render(): String = buildString {
            append("Página ").append(page)
            if (exercises.isNotEmpty()) append(": ").append(exerciseList(exercises))
        }
    }

    private data class OrphanLine(
        val exercises: MutableList<String> = mutableListOf(),
    ) : Line {
        override fun render(): String = if (exercises.size == 1) {
            "Ejercicio sin página: ${exercises.single()}"
        } else {
            "Ejercicios sin página: ${naturalList(exercises)}"
        }
    }

    fun compose(claims: List<EvidenceClaim>): String {
        val relevant = claims.filter { claim ->
            if (!claim.active) return@filter false
            val field = (StatusFieldPolicy.target(claim.category, claim.status) as? FieldTarget.Field)?.field
            (claim.category == ClaimCategory.PAGE && field == DiaryField.PAGES) ||
                (claim.category == ClaimCategory.EXERCISE && field == DiaryField.EXERCISES)
        }
        val lines = mutableListOf<Line>()

        orderedBlocks(relevant).forEach { blockClaims ->
            val ordered = blockClaims.sortedWith(claimComparator())
            val pages = ordered
                .filter { it.category == ClaimCategory.PAGE }
                .associateByTo(LinkedHashMap()) { pageLabel(it) }
                .mapValuesTo(LinkedHashMap()) { PageLine(it.key) }
            var currentPage: PageLine? = null
            var currentOrphans: OrphanLine? = null

            ordered.forEach { claim ->
                when (claim.category) {
                    ClaimCategory.PAGE -> {
                        val line = pages.getValue(pageLabel(claim))
                        if (!line.appended) {
                            lines += line
                            line.appended = true
                        }
                        currentPage = line
                        currentOrphans = null
                    }
                    ClaimCategory.EXERCISE -> {
                        val exercise = exerciseLabel(claim.value)
                        if (exercise.isEmpty()) return@forEach
                        val relatedPage = explicitPage(claim.value) ?: pageForExercise(claim)
                        val activePage = currentPage
                        val destination = when {
                            relatedPage != null -> pages[relatedPage]?.exercises ?: run {
                                val orphan = currentOrphans ?: OrphanLine().also {
                                    lines += it
                                    currentOrphans = it
                                }
                                orphan.exercises
                            }
                            activePage != null -> activePage.exercises
                            else -> {
                                val orphan = currentOrphans ?: OrphanLine().also {
                                    lines += it
                                    currentOrphans = it
                                }
                                orphan.exercises
                            }
                        }
                        if (exercise !in destination) destination += exercise
                    }
                    else -> Unit
                }
            }
        }
        return lines.joinToString("\n") { it.render() }.trim()
    }

    private fun orderedBlocks(claims: List<EvidenceClaim>): List<List<EvidenceClaim>> {
        val blocks = claims.groupByTo(LinkedHashMap()) { primaryEvidence(it).blockId.value }.values.toList()
        return if (blocks.all { block -> block.all { primaryEvidence(it).blockOrdinal != null } }) {
            blocks.sortedBy { primaryEvidence(it.first()).blockOrdinal }
        } else blocks
    }

    private fun claimComparator(): Comparator<EvidenceClaim> = compareBy(
        { primaryEvidence(it).audioSegmentOrdinal ?: Int.MAX_VALUE },
        { primaryEvidence(it).spanOrdinal ?: Int.MAX_VALUE },
        { primaryEvidence(it).startMs },
        { mentionPosition(it) },
    )

    /** Breaks ties when several claims cite the same Whisper span. */
    private fun mentionPosition(claim: EvidenceClaim): Int {
        val excerpt = WordNumbers.normalize(primaryEvidence(claim).excerpt)
        val value = when (claim.category) {
            ClaimCategory.PAGE -> pageLabel(claim)
            ClaimCategory.EXERCISE -> exerciseLabel(claim.value)
            else -> return Int.MAX_VALUE
        }
        val positions = when (claim.category) {
            ClaimCategory.PAGE -> pageMentions(excerpt).filter { it.value == value }.map { it.index }
            ClaimCategory.EXERCISE -> exerciseMentions(excerpt).filter { value in it.values }.map { it.index }
            else -> emptyList()
        }
        return positions.firstOrNull() ?: Int.MAX_VALUE
    }

    private fun pageForExercise(claim: EvidenceClaim): String? {
        val excerpt = WordNumbers.normalize(primaryEvidence(claim).excerpt)
        val exercise = exerciseLabel(claim.value)
        val pages = pageMentions(excerpt)
        val occurrence = exerciseMentions(excerpt).firstOrNull { exercise in it.values } ?: return null
        return pages.lastOrNull { it.index < occurrence.index }?.value
            ?: pages.firstOrNull { it.index > occurrence.index }?.value
    }

    private fun pageMentions(text: String): List<PageMention> = PAGE.findAll(text).mapNotNull { match ->
        val value = numbers.bareValues(text.substring(match.range.last + 1), NumberKind.PAGE).firstOrNull()
        value?.let { PageMention(match.range.first, it) }
    }.toList()

    private fun exerciseMentions(text: String): List<ExerciseMention> {
        val anchors = EXERCISE.findAll(text).toList()
        return anchors.mapIndexed { index, match ->
            val nextPage = PAGE.find(text, match.range.last + 1)?.range?.first ?: text.length
            val nextExercise = anchors.getOrNull(index + 1)?.range?.first ?: text.length
            val tail = text.substring(match.range.last + 1, minOf(nextPage, nextExercise))
            ExerciseMention(match.range.first, numbers.bareValues(tail, NumberKind.EXERCISE))
        }
    }

    private fun primaryEvidence(claim: EvidenceClaim): EvidenceRef =
        claim.evidences.firstOrNull { !it.contextual } ?: claim.evidence

    private fun pageLabel(claim: EvidenceClaim): String {
        val source = claim.normalizedValue.ifBlank { claim.value }
        return Regex("\\d+[a-z]?").find(source)?.value ?: claim.value.trim()
    }

    private fun explicitPage(value: String): String? =
        Regex("\\(p\\.\\s*(\\d+[a-z]?)\\)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)

    private fun exerciseLabel(value: String): String = value
        .replace(Regex("\\s*\\(p\\.[^)]*\\)\\s*$"), "")
        .replace(Regex("^(?:ejercicios?|actividades?)\\s+", RegexOption.IGNORE_CASE), "")
        .trim()

    private data class PageMention(val index: Int, val value: String)
    private data class ExerciseMention(val index: Int, val values: List<String>)

    private companion object {
        private val PAGE = Regex("\\b(?:pagina|paginas|pag)\\b")
        private val EXERCISE = Regex("\\b(?:ejercicio|ejercicios|actividad|actividades)\\b")

        fun exerciseList(values: List<String>): String =
            if (values.size == 1) "ejercicio ${values.single()}" else "ejercicios ${naturalList(values)}"

        fun naturalList(values: List<String>): String = when (values.size) {
            0 -> ""
            1 -> values.single()
            2 -> "${values[0]} y ${values[1]}"
            else -> values.dropLast(1).joinToString(", ") + " y " + values.last()
        }
    }
}

private typealias WordNumbers = com.capo.diarioclase.processing.evidence.SpanishNumberNormalizer

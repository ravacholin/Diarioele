package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.processing.semantic.DiaryField
import com.capo.diarioclase.processing.semantic.FieldTarget
import com.capo.diarioclase.processing.semantic.StatusFieldPolicy

/**
 * Combines performed pages and exercises in evidence order. A block change clears page
 * context, and claims routed to confirmation, homework or inactive history never affect it.
 */
class PagesAndExercisesComposer {

    private sealed interface Line {
        fun render(): String
    }

    private data class PageLine(
        val page: String,
        val exercises: MutableList<String> = mutableListOf(),
    ) : Line {
        override fun render(): String =
            if (exercises.isEmpty()) page else "$page (${exercises.joinToString(", ")})"
    }

    private data class OrphanLine(
        val exercises: MutableList<String> = mutableListOf(),
    ) : Line {
        override fun render(): String = exercises.joinToString(", ")
    }

    fun compose(claims: List<EvidenceClaim>): String {
        val lines = mutableListOf<Line>()
        var currentBlock: String? = null
        var currentPage: PageLine? = null
        var currentOrphans: OrphanLine? = null

        orderedByEvidence(claims).forEach { claim ->
            val block = claim.evidences.firstOrNull()?.blockId?.value ?: claim.evidence.blockId.value
            if (block != currentBlock) {
                currentBlock = block
                currentPage = null
                currentOrphans = null
            }
            if (!claim.active) return@forEach

            val field = (StatusFieldPolicy.target(claim.category, claim.status) as? FieldTarget.Field)?.field
            when {
                claim.category == ClaimCategory.PAGE && field == DiaryField.PAGES -> {
                    val page = pageLabel(claim)
                    val existing = currentPage?.takeIf { it.page == page }
                    currentPage = existing ?: PageLine(page).also { lines += it }
                    currentOrphans = null
                }

                claim.category == ClaimCategory.EXERCISE && field == DiaryField.EXERCISES -> {
                    val exercise = exerciseLabel(claim.value)
                    if (exercise.isEmpty()) return@forEach
                    val destination = currentPage?.exercises ?: run {
                        val orphan = currentOrphans ?: OrphanLine().also {
                            lines += it
                            currentOrphans = it
                        }
                        orphan.exercises
                    }
                    if (exercise !in destination) destination += exercise
                }
            }
        }

        return lines.joinToString("\n") { it.render() }.trim()
    }

    private fun orderedByEvidence(claims: List<EvidenceClaim>): List<EvidenceClaim> {
        val blocks = claims.groupByTo(LinkedHashMap()) { primaryEvidence(it).blockId.value }
            .values
            .toList()
        val orderedBlocks = if (blocks.all { block -> block.all { primaryEvidence(it).blockOrdinal != null } }) {
            blocks.sortedBy { block -> primaryEvidence(block.first()).blockOrdinal }
        } else {
            blocks
        }
        return orderedBlocks.flatMap { block ->
            val hasCompleteOrder = block.all { claim ->
                val evidence = primaryEvidence(claim)
                evidence.audioSegmentOrdinal != null && evidence.spanOrdinal != null
            }
            if (hasCompleteOrder) {
                block.sortedWith(
                    compareBy(
                        { primaryEvidence(it).audioSegmentOrdinal },
                        { primaryEvidence(it).spanOrdinal },
                    ),
                )
            } else {
                block
            }
        }
    }

    private fun primaryEvidence(claim: EvidenceClaim): EvidenceRef =
        claim.evidences.firstOrNull { !it.contextual } ?: claim.evidence

    private fun pageLabel(claim: EvidenceClaim): String {
        val source = claim.normalizedValue.ifBlank { claim.value }
        return Regex("\\d+").find(source)?.value ?: claim.value.trim()
    }

    private fun exerciseLabel(value: String): String = value
        .replace(Regex("\\s*\\(p\\.[^)]*\\)\\s*$"), "")
        .replace(Regex("^(?:ejercicios?|actividades?)\\s+", RegexOption.IGNORE_CASE), "")
        .trim()
}

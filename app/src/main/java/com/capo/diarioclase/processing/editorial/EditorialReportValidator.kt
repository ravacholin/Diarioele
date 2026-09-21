package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimStatus

enum class EditorialIssue {
    MALFORMED_CONTRACT,
    UNKNOWN_CLAIM,
    DUPLICATE_REFERENCE,
    MISSING_CLAIM,
    WRONG_DESTINATION,
    MISSING_LITERAL,
    PROTECTED_CLAIM_DISCARDED,
    CONFLICTING_DECISION,
}

sealed interface EditorialValidationOutcome {
    data class Valid(val report: EditorialReport) : EditorialValidationOutcome
    data class Invalid(
        val issues: Set<EditorialIssue>,
        val missingClaimIds: Set<String>,
    ) : EditorialValidationOutcome
}

class EditorialReportValidator {
    fun validate(rawJson: String, request: EditorialReportRequest): EditorialValidationOutcome {
        val report = try {
            EditorialReportCodec.decode(rawJson)
        } catch (_: EditorialContractException) {
            return EditorialValidationOutcome.Invalid(setOf(EditorialIssue.MALFORMED_CONTRACT), emptySet())
        }
        val issues = linkedSetOf<EditorialIssue>()
        val known = request.items.associateBy { it.claimId }
        val usages = mutableMapOf<String, MutableList<Usage>>()
        fun record(id: String, usage: Usage) {
            usages.getOrPut(id) { mutableListOf() } += usage
            if (id !in known) issues += EditorialIssue.UNKNOWN_CLAIM
        }

        if (report.summarySourceClaimIds.hasDuplicates()) issues += EditorialIssue.DUPLICATE_REFERENCE
        if (report.summary.isBlank() != report.summarySourceClaimIds.isEmpty()) {
            issues += EditorialIssue.CONFLICTING_DECISION
        }
        report.summarySourceClaimIds.forEach { record(it, Usage(EditorialSection.SUMMARY, report.summary)) }
        report.material.forEach { output ->
            if (output.sourceClaimIds.hasDuplicates()) issues += EditorialIssue.DUPLICATE_REFERENCE
            output.sourceClaimIds.forEach { record(it, Usage(EditorialSection.MATERIAL, output.text)) }
        }
        report.homework.forEach { output ->
            if (output.sourceClaimIds.hasDuplicates()) issues += EditorialIssue.DUPLICATE_REFERENCE
            output.sourceClaimIds.forEach { record(it, Usage(EditorialSection.HOMEWORK, output.text)) }
        }

        val discardedIds = report.discarded.map(EditorialDiscard::claimId)
        if (discardedIds.hasDuplicates()) issues += EditorialIssue.DUPLICATE_REFERENCE
        report.discarded.forEach { discard ->
            record(discard.claimId, Usage(null, discard.reason))
            val item = known[discard.claimId]
            if (item != null && item.category in PROTECTED_CATEGORIES) {
                issues += EditorialIssue.PROTECTED_CLAIM_DISCARDED
            }
        }

        val missing = known.keys.filterTo(linkedSetOf()) { it !in usages }
        if (missing.isNotEmpty()) issues += EditorialIssue.MISSING_CLAIM

        request.items.forEach { item ->
            val itemUsages = usages[item.claimId].orEmpty()
            val visible = itemUsages.filter { it.section != null }
            val discarded = itemUsages.any { it.section == null }
            if (discarded && visible.isNotEmpty()) issues += EditorialIssue.CONFLICTING_DECISION

            val expected = expectedSection(item.category, item.status)
            if (expected != null) {
                if (visible.isNotEmpty() && visible.none { it.section == expected }) {
                    issues += EditorialIssue.WRONG_DESTINATION
                }
                if (expected == EditorialSection.HOMEWORK && visible.any { it.section == EditorialSection.MATERIAL }) {
                    issues += EditorialIssue.WRONG_DESTINATION
                }
                if (expected == EditorialSection.MATERIAL && visible.any { it.section == EditorialSection.HOMEWORK }) {
                    issues += EditorialIssue.WRONG_DESTINATION
                }
            } else if (visible.any { it.section != EditorialSection.SUMMARY }) {
                issues += EditorialIssue.WRONG_DESTINATION
            }

            if (item.category == ClaimCategory.PAGE || item.category == ClaimCategory.EXERCISE) {
                visible.filter { it.section == expected }.forEach { usage ->
                    if (!containsNormalizedLiteral(usage.text, item.normalizedValue)) {
                        issues += EditorialIssue.MISSING_LITERAL
                    }
                }
            }
        }

        return if (issues.isEmpty()) {
            EditorialValidationOutcome.Valid(report)
        } else {
            EditorialValidationOutcome.Invalid(issues, missing)
        }
    }

    private fun expectedSection(category: ClaimCategory, status: ClaimStatus): EditorialSection? = when {
        status == ClaimStatus.ASSIGNED -> EditorialSection.HOMEWORK
        category == ClaimCategory.HOMEWORK -> EditorialSection.HOMEWORK
        category == ClaimCategory.PAGE || category == ClaimCategory.EXERCISE -> EditorialSection.MATERIAL
        else -> null
    }

    private fun containsNormalizedLiteral(text: String, normalizedValue: String): Boolean {
        val numberTokens = Regex("\\d+").findAll(normalizedValue).map { it.value }.toList()
        val letterTokens = Regex("(?<![\\p{L}\\d])\\p{L}(?![\\p{L}\\d])")
            .findAll(normalizedValue)
            .map { it.value }
            .toList()
        val tokens = numberTokens + letterTokens
        if (tokens.isEmpty()) return text.contains(normalizedValue, ignoreCase = true)
        return tokens.all { token ->
            val boundary = if (token.all(Char::isDigit)) "(?<!\\d)${Regex.escape(token)}(?!\\d)"
            else "(?<!\\p{L})${Regex.escape(token)}(?!\\p{L})"
            Regex(boundary, RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
    }

    private fun <T> List<T>.hasDuplicates(): Boolean = size != toSet().size

    private data class Usage(val section: EditorialSection?, val text: String)

    companion object {
        const val VERSION = "editorial-validator-v1"
        private val PROTECTED_CATEGORIES =
            setOf(ClaimCategory.PAGE, ClaimCategory.EXERCISE, ClaimCategory.HOMEWORK)
    }
}

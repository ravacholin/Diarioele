package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.semantic.ClaimProvenance

enum class EditorialSection { SUMMARY, MATERIAL, HOMEWORK }

enum class EditorialReportState { GENERATING, READY, STALE, FAILED }

data class EditorialEvidenceItem(
    val claimId: String,
    val category: ClaimCategory,
    val status: ClaimStatus,
    val value: String,
    val normalizedValue: String,
    val excerpt: String,
    val blockOrdinal: Int?,
    val segmentOrdinal: Int?,
    val spanOrdinal: Int?,
    val origin: ClaimOrigin,
    val provenance: ClaimProvenance,
    val supersedesClaimKeys: List<String>,
)

data class EditorialReportRequest(
    val sessionId: String,
    val inputHash: String,
    val promptVersion: String = PROMPT_VERSION,
    val schemaVersion: String = SCHEMA_VERSION,
    val items: List<EditorialEvidenceItem>,
) {
    companion object {
        const val PROMPT_VERSION = "editorial-prompt-v1"
        const val SCHEMA_VERSION = "editorial-schema-v1"
    }
}

data class EditorialOutputItem(
    val text: String,
    val sourceClaimIds: List<String>,
)

data class EditorialDiscard(
    val claimId: String,
    val reason: String,
)

data class EditorialReport(
    val summary: String,
    val material: List<EditorialOutputItem>,
    val homework: List<EditorialOutputItem>,
    val summarySourceClaimIds: List<String>,
    val discarded: List<EditorialDiscard>,
)

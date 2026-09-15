package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.data.db.BlockId

enum class ClaimCategory { TOPIC, ACTIVITY, PAGE, EXERCISE, HOMEWORK }
enum class ClaimStatus { PERFORMED, ASSIGNED, PROPOSED, CANCELLED, CORRECTED, UNCERTAIN }

/**
 * Origen de un claim. `SEMANTIC` se conserva como legacy para no romper filas Room ya
 * existentes; los orígenes remotos de la Fase 5 son `GEMINI`, `GROQ` y `OPENROUTER`.
 */
enum class ClaimOrigin { LOCAL_RULE, SEMANTIC, MANUAL_MARKER, USER_EDIT, GEMINI, GROQ, OPENROUTER }
enum class InterpretationMode { CONSERVATIVE, BALANCED, EXHAUSTIVE }

data class EvidenceRef(val blockId: BlockId, val startMs: Long, val endMs: Long, val excerpt: String)
data class RawClaim(
    val id: String,
    val category: ClaimCategory,
    val value: String,
    val normalizedValue: String,
    val status: ClaimStatus,
    val confidence: Double,
    val origin: ClaimOrigin,
    val evidence: EvidenceRef,
    // Campos de Fase 5 (aditivos, con default para no romper llamadas existentes).
    val claimKey: String = id,
    val evidences: List<EvidenceRef> = listOf(evidence),
    val supersedesClaimKeys: List<String> = emptyList(),
)
data class EvidenceClaim(
    val id: String,
    val category: ClaimCategory,
    val value: String,
    val normalizedValue: String,
    val status: ClaimStatus,
    val confidence: Double,
    val origin: ClaimOrigin,
    val evidence: EvidenceRef,
    val active: Boolean = true,
    // Campos de Fase 5 (aditivos, con default).
    val claimKey: String = id,
    val evidences: List<EvidenceRef> = listOf(evidence),
    val supersedesClaimKeys: List<String> = emptyList(),
)
data class ClaimPresentation(val accepted: List<EvidenceClaim>, val confirm: List<EvidenceClaim>, val hidden: List<EvidenceClaim>) {
    val visibleCount get() = accepted.size + confirm.size
}
data class DiaryDraft(
    val sessionId: String,
    val mode: InterpretationMode,
    val topics: String,
    val activities: String,
    val pages: String,
    val exercises: String,
    val homework: String,
    val accepted: List<EvidenceClaim>,
    val confirm: List<EvidenceClaim>,
)

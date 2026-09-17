package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.semantic.ClaimProvenance

enum class ClaimCategory { TOPIC, ACTIVITY, PAGE, EXERCISE, HOMEWORK }
enum class ClaimStatus { PERFORMED, ASSIGNED, PROPOSED, CANCELLED, CORRECTED, UNCERTAIN }

/**
 * Origen de un claim. `SEMANTIC` se conserva como legacy para no romper filas Room ya
 * existentes; los orígenes remotos de la Fase 5 son `GEMINI`, `GROQ` y `OPENROUTER`.
 */
enum class ClaimOrigin { LOCAL_RULE, SEMANTIC, MANUAL_MARKER, USER_EDIT, GEMINI, GROQ, OPENROUTER }
enum class InterpretationMode { CONSERVATIVE, BALANCED, EXHAUSTIVE }

data class EvidenceRef(
    val blockId: BlockId,
    val startMs: Long,
    val endMs: Long,
    val excerpt: String,
    val blockOrdinal: Int? = null,
    val audioSegmentOrdinal: Int? = null,
    val spanOrdinal: Int? = null,
    val contextual: Boolean = false,
)
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
    // Identidad y calidad aditivas de Fase 5.2.
    val runId: String? = null,
    val packetId: String? = null,
    val providerClaimKey: String = claimKey,
    val declaredConfidence: Double = confidence,
    val effectiveConfidence: Double = confidence,
    val transcriptSpanIds: List<String> = emptyList(),
    val claimOrdinal: Int = 0,
    // Cita literal del fragmento que respalda el claim (Nivel 2). Solo se conserva si el
    // validador la verificó como substring real de un span citado. No se persiste.
    val evidenceQuote: String? = null,
    // Justificación breve del claim (Nivel 3). Se persiste y se muestra al tocar el elemento.
    val reason: String? = null,
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
    val runId: String? = null,
    val packetId: String? = null,
    val providerClaimKey: String = claimKey,
    val declaredConfidence: Double = confidence,
    val effectiveConfidence: Double = confidence,
    val transcriptSpanIds: List<String> = emptyList(),
    val claimOrdinal: Int = 0,
    // Procedencia de la fusión híbrida local + remota (Fase 6, Q3).
    val provenance: ClaimProvenance = ClaimProvenance.REMOTE,
    // Cita literal verificada que respalda el claim (Nivel 2). Transitoria: no se persiste.
    val evidenceQuote: String? = null,
    // Justificación breve del claim (Nivel 3). Se persiste.
    val reason: String? = null,
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
    // Resumen de la clase generado por IA (Nivel 3). Se persiste. Vacío si no hubo remoto.
    val summary: String = "",
)

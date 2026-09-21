package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.RawClaim
import com.capo.diarioclase.processing.evidence.SpanishNumberNormalizer as WordNumbers

/**
 * Valida una respuesta cruda de un proveedor contra los spans locales del paquete (Task 5).
 *
 * Nunca acepta evidencia textual inventada por el modelo: cada `EvidenceRef` se construye
 * desde los spans locales referenciados por `evidence_span_ids`. Un claim solo avanza si
 * cumple todas las reglas del diseño; de lo contrario la respuesta completa es inválida y
 * el router (Task 7) sigue con el próximo proveedor o el fallback local.
 */
class SemanticResponseValidator(
    private val maxClaims: Int = 100,
    private val maxValueLength: Int = 300,
) {

    fun validate(
        rawJson: String,
        provider: InferenceProvider,
        spans: List<PublicTranscriptSpan>,
        blockIdOf: (PublicTranscriptSpan) -> BlockId = { BlockId("B${it.blockOrdinal}") },
    ): ValidationOutcome {
        val parsed = try {
            ProviderClaimsCodec.decode(rawJson)
        } catch (e: ContractParseException) {
            return ValidationOutcome.Invalid(ValidationFailure.MALFORMED_JSON)
        }

        if (parsed.size > maxClaims) return invalid(ValidationFailure.TOO_MANY_CLAIMS)

        val spanById = spans.associateBy { it.publicId }
        val claimKeys = parsed.map { it.claimKey }
        if (claimKeys.any { it.isBlank() }) return invalid(ValidationFailure.INVALID_CLAIM_KEY)
        if (claimKeys.toSet().size != claimKeys.size) return invalid(ValidationFailure.DUPLICATE_CLAIM_KEY)
        val claimKeySet = claimKeys.toSet()

        val origin = provider.toOrigin()
        val result = ArrayList<RawClaim>(parsed.size)

        parsed.forEach { claim ->
            val category = runCatching { ClaimCategory.valueOf(claim.category) }.getOrNull()
                ?: return invalid(ValidationFailure.UNKNOWN_CATEGORY)
            val status = runCatching { ClaimStatus.valueOf(claim.status) }.getOrNull()
                ?: return invalid(ValidationFailure.UNKNOWN_STATUS)
            if (claim.confidence !in 0.0..1.0) return invalid(ValidationFailure.CONFIDENCE_OUT_OF_RANGE)
            if (claim.value.isBlank() || claim.value.length > maxValueLength) {
                return invalid(ValidationFailure.INVALID_VALUE)
            }
            if (claim.evidenceSpanIds.isEmpty()) return invalid(ValidationFailure.MISSING_EVIDENCE)

            val evidenceSpans = claim.evidenceSpanIds.map { spanId ->
                spanById[spanId] ?: return invalid(ValidationFailure.UNKNOWN_EVIDENCE)
            }.sortedWith(SpanOrder)
            if (evidenceSpans.none { !it.contextOnly }) {
                return invalid(ValidationFailure.CONTEXT_ONLY_EVIDENCE)
            }

            // Supersesiones: existentes, no autorreferentes, dentro del alcance.
            claim.supersedesClaimKeys.forEach { key ->
                if (key == claim.claimKey) return invalid(ValidationFailure.SELF_SUPERSEDE)
                if (key !in claimKeySet) return invalid(ValidationFailure.UNKNOWN_SUPERSEDE)
            }
            if (claim.supersedesClaimKeys.toSet().size != claim.supersedesClaimKeys.size) {
                return invalid(ValidationFailure.DUPLICATE_SUPERSEDE)
            }

            val evidences = evidenceSpans.map { span ->
                EvidenceRef(
                    blockId = blockIdOf(span),
                    startMs = span.startMs,
                    endMs = span.endMs,
                    excerpt = span.text,
                    blockOrdinal = span.blockOrdinal,
                    audioSegmentOrdinal = span.audioSegmentOrdinal,
                    spanOrdinal = span.spanOrdinal,
                    contextual = span.contextOnly,
                )
            }
            result += RawClaim(
                id = claim.claimKey,
                category = category,
                value = claim.value,
                normalizedValue = claim.normalizedValue.ifBlank { claim.value },
                status = status,
                confidence = claim.confidence,
                origin = origin,
                evidence = firstNonContextEvidence(evidenceSpans, evidences),
                claimKey = claim.claimKey,
                evidences = evidences,
                supersedesClaimKeys = claim.supersedesClaimKeys,
                evidenceQuote = verifiedQuote(claim.evidenceQuote, evidenceSpans),
                reason = claim.reason,
            )
        }

        if (hasSupersessionCycle(parsed)) return invalid(ValidationFailure.SUPERSEDE_CYCLE)

        return ValidationOutcome.Valid(result, ProviderClaimsCodec.summaryOf(rawJson))
    }

    /**
     * Conserva [quote] solo si su forma normalizada es substring de un span citado no-contexto.
     * Así una cita inventada por el modelo nunca sirve para anclar un número (anti-invención);
     * una cita real habilita el rescate del claim en la fusión (Nivel 2). No usa red.
     */
    private fun verifiedQuote(quote: String?, evidenceSpans: List<PublicTranscriptSpan>): String? {
        val normalized = quote?.let { WordNumbers.normalize(it) }?.takeIf { it.isNotBlank() } ?: return null
        val backed = evidenceSpans.any { !it.contextOnly && WordNumbers.normalize(it.text).contains(normalized) }
        return if (backed) quote else null
    }

    private fun firstNonContextEvidence(
        spans: List<PublicTranscriptSpan>,
        evidences: List<EvidenceRef>,
    ): EvidenceRef {
        val index = spans.indexOfFirst { !it.contextOnly }
        return if (index >= 0) evidences[index] else evidences.first()
    }

    private fun hasSupersessionCycle(claims: List<ProviderSemanticClaim>): Boolean {
        val edges = claims.associate { it.claimKey to it.supersedesClaimKeys }
        val visiting = HashSet<String>()
        val done = HashSet<String>()
        fun dfs(node: String): Boolean {
            if (node in done) return false
            if (!visiting.add(node)) return true
            edges[node].orEmpty().forEach { next -> if (dfs(next)) return true }
            visiting.remove(node)
            done.add(node)
            return false
        }
        return claims.any { dfs(it.claimKey) }
    }

    private fun invalid(reason: ValidationFailure) = ValidationOutcome.Invalid(reason)
}

sealed interface ValidationOutcome {
    data class Valid(val claims: List<RawClaim>, val summary: String? = null) : ValidationOutcome
    data class Invalid(val reason: ValidationFailure) : ValidationOutcome
}

enum class ValidationFailure {
    MALFORMED_JSON,
    TOO_MANY_CLAIMS,
    UNKNOWN_CATEGORY,
    UNKNOWN_STATUS,
    CONFIDENCE_OUT_OF_RANGE,
    INVALID_VALUE,
    MISSING_EVIDENCE,
    UNKNOWN_EVIDENCE,
    CONTEXT_ONLY_EVIDENCE,
    INVALID_CLAIM_KEY,
    DUPLICATE_CLAIM_KEY,
    SELF_SUPERSEDE,
    UNKNOWN_SUPERSEDE,
    DUPLICATE_SUPERSEDE,
    SUPERSEDE_CYCLE,
}

private fun InferenceProvider.toOrigin(): ClaimOrigin = when (this) {
    InferenceProvider.GEMINI -> ClaimOrigin.GEMINI
    InferenceProvider.GROQ -> ClaimOrigin.GROQ
    InferenceProvider.OPENROUTER -> ClaimOrigin.OPENROUTER
}

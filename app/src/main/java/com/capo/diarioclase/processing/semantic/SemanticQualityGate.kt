package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.RawClaim

/**
 * Gate de calidad semántica del quality loop (Task Q1).
 *
 * Recibe el paquete (evidencia local), los claims remotos ya validados estructuralmente
 * (Task 5) y los candidatos literales locales, y decide si la respuesta remota puede
 * aceptarse, necesita un único reintento correctivo o hay que caer al fallback local.
 *
 * Dos reglas de anclaje concretas en v1:
 * - Un número de página o ejercicio que un claim remoto afirma pero que no aparece en la
 *   evidencia local es una [SemanticIssue.NUMERIC_EVIDENCE_MISMATCH]. Una discrepancia
 *   numérica no se compensa con confianza: nunca produce ACCEPT.
 * - Una respuesta remota vacía cuando existe señal local (un candidato literal) es una
 *   [SemanticIssue.EMPTY_DESPITE_LOCAL_SIGNAL]: vale la pena un reintento con el aviso.
 *
 * La confianza efectiva es determinista y está versionada como [SCORE_VERSION]; no consulta
 * la red ni depende de estado externo, así el mismo paquete siempre produce el mismo veredicto.
 */
class SemanticQualityGate(
    private val normalizer: SpanishNumberNormalizer = SpanishNumberNormalizer(),
) {

    /**
     * Evalúa la respuesta remota contra la evidencia y los candidatos locales.
     *
     * @param repairAlreadyAttempted true cuando esta respuesta ya es el resultado del único
     *   reintento correctivo permitido; si aún quedan issues, el veredicto es FALLBACK en
     *   lugar de pedir una segunda reparación (Q2 no permite un tercer intento).
     */
    fun evaluate(
        packet: InterpretationPacket,
        remote: List<RawClaim>,
        local: List<RawClaim>,
        repairAlreadyAttempted: Boolean = false,
    ): QualityReport {
        val scored = scoreInternal(packet, remote, local)

        val issues = LinkedHashSet<SemanticIssue>()
        if (remote.isEmpty() && local.isNotEmpty()) {
            issues += SemanticIssue.EMPTY_DESPITE_LOCAL_SIGNAL
        }
        if (scored.any { it.numericMismatch }) {
            issues += SemanticIssue.NUMERIC_EVIDENCE_MISMATCH
        }

        val effectiveConfidence =
            if (scored.isEmpty()) 0.0 else scored.map { it.claim.effectiveConfidence }.average()

        val decision = when {
            issues.isEmpty() -> QualityDecision.ACCEPT
            repairAlreadyAttempted -> QualityDecision.FALLBACK
            else -> QualityDecision.REPAIR
        }

        return QualityReport(decision, issues.toList(), effectiveConfidence)
    }

    /**
     * Devuelve los claims remotos anotados con su confianza efectiva ([SCORE_VERSION]).
     * La fusión híbrida (Q3) reutiliza este cálculo para ordenar candidatos equivalentes.
     */
    fun score(packet: InterpretationPacket, remote: List<RawClaim>, local: List<RawClaim>): List<RawClaim> =
        scoreInternal(packet, remote, local).map { it.claim }

    private fun scoreInternal(
        packet: InterpretationPacket,
        remote: List<RawClaim>,
        local: List<RawClaim>,
    ): List<ScoredClaim> {
        val evidenceByKind = NumberKind.values().associateWith { kind ->
            packet.request.spans
                .filterNot { it.contextOnly }
                .flatMap { normalizer.values(it.text, kind) }
                .toSet()
        }

        return remote.map { claim ->
            val kind = kindOf(claim.category)
            val claimNumbers = kind?.let { numbersOf(claim, it) } ?: emptyList()
            val grounded = when {
                kind == null -> true
                claimNumbers.isEmpty() -> false
                else -> claimNumbers.all { it in evidenceByKind.getValue(kind) }
            }
            val numericMismatch = kind != null && !grounded

            val localAgreement = if (kind == null) {
                if (local.any { it.category == claim.category && it.normalizedValue == claim.normalizedValue }) 1.0 else 0.0
            } else {
                val shares = local.any { candidate ->
                    candidate.category == claim.category &&
                        numbersOf(candidate, kind).any { it in claimNumbers }
                }
                if (shares) 1.0 else 0.0
            }

            val declared = claim.declaredConfidence
            val evidenceStrength = if (grounded) 1.0 else 0.0
            val chronologyScore = 1.0
            val contradictionPenalty = if (numericMismatch) NUMERIC_MISMATCH_PENALTY else 0.0

            val effective = (
                declared * WEIGHT_DECLARED +
                    evidenceStrength * WEIGHT_EVIDENCE +
                    localAgreement * WEIGHT_LOCAL_AGREEMENT +
                    chronologyScore * WEIGHT_CHRONOLOGY -
                    contradictionPenalty
                ).coerceIn(0.0, 1.0)

            ScoredClaim(
                claim = claim.copy(declaredConfidence = declared, effectiveConfidence = effective),
                numericMismatch = numericMismatch,
            )
        }
    }

    private fun numbersOf(claim: RawClaim, kind: NumberKind): List<String> {
        val fromValue = normalizer.bareValues(claim.value, kind)
        return fromValue.ifEmpty { normalizer.bareValues(claim.normalizedValue, kind) }
    }

    private fun kindOf(category: ClaimCategory): NumberKind? = when (category) {
        ClaimCategory.PAGE -> NumberKind.PAGE
        ClaimCategory.EXERCISE -> NumberKind.EXERCISE
        else -> null
    }

    private data class ScoredClaim(val claim: RawClaim, val numericMismatch: Boolean)

    companion object {
        /** Versión del cálculo de confianza efectiva. Un cambio de pesos incrementa esta versión. */
        const val SCORE_VERSION = "quality-score-v1"

        // Pesos congelados de quality-score-v1. Suman 1.0 antes de la penalización.
        private const val WEIGHT_DECLARED = 0.35
        private const val WEIGHT_EVIDENCE = 0.30
        private const val WEIGHT_LOCAL_AGREEMENT = 0.25
        private const val WEIGHT_CHRONOLOGY = 0.10

        /** Una discrepancia numérica no puede compensarse con confianza declarada. */
        private const val NUMERIC_MISMATCH_PENALTY = 0.5
    }
}

package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.RawClaim

/**
 * Reduce claims validados a claims presentables (Task 5).
 *
 * Conserva el orden de entrada (bloque y orden de llegada); **nunca** reordena por
 * `startMs`. Marca inactivo:
 * - todo claim cuyo `claimKey` aparece en las supersesiones de otro (una corrección o un
 *   movimiento a tarea afecta solo al elemento referido), y
 * - los duplicados exactos por categoría y valor normalizado, dejando activo el último.
 *
 * No borra los reemplazados: quedan inactivos como historial.
 */
class SemanticClaimReducer {

    fun reduce(claims: List<RawClaim>): List<EvidenceClaim> {
        val superseded = claims.flatMap { it.supersedesClaimKeys }.toSet()

        val lastActiveIndex = HashMap<Pair<ClaimCategory, String>, Int>()
        claims.forEachIndexed { index, claim ->
            if (claim.claimKey !in superseded) {
                lastActiveIndex[claim.category to claim.normalizedValue] = index
            }
        }

        return claims.mapIndexed { index, claim ->
            val active = claim.claimKey !in superseded &&
                lastActiveIndex[claim.category to claim.normalizedValue] == index
            EvidenceClaim(
                id = claim.id,
                category = claim.category,
                value = claim.value,
                normalizedValue = claim.normalizedValue,
                status = claim.status,
                confidence = claim.confidence,
                origin = claim.origin,
                evidence = claim.evidence,
                active = active,
                claimKey = claim.claimKey,
                evidences = claim.evidences,
                supersedesClaimKeys = claim.supersedesClaimKeys,
                runId = claim.runId,
                packetId = claim.packetId,
                providerClaimKey = claim.providerClaimKey,
                declaredConfidence = claim.declaredConfidence,
                effectiveConfidence = claim.effectiveConfidence,
                transcriptSpanIds = claim.transcriptSpanIds,
                claimOrdinal = claim.claimOrdinal,
            )
        }
    }
}

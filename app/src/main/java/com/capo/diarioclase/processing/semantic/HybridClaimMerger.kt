package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.RawClaim

/**
 * Fusión híbrida de candidatos locales deterministas y claims remotos aceptados (Fase 6, Q3).
 *
 * Reglas:
 * - Clave canónica: categoría + valor central (para PAGE/EXERCISE, el número; para el resto,
 *   el valor normalizado). El estado no entra en la clave, así un mismo ejercicio marcado
 *   PERFORMED por lo local y ASSIGNED por lo remoto se funde: el remoto refina el estado.
 * - Coincidencia local + remoto → procedencia [ClaimProvenance.BOTH], base remota (conserva su
 *   valor/estado/origen), confianza efectiva = máximo de ambas, evidencia unida.
 * - Un candidato local cuya evidencia ya fue citada por algún claim remoto se descarta: el
 *   remoto interpretó esos spans. Un candidato local sobre spans que el remoto no citó se
 *   conserva como [ClaimProvenance.LOCAL] (rellena huecos).
 * - Conflicto de página: si hay una página local (o BOTH) y una página solo remota con un
 *   número distinto, la remota pasa a UNCERTAIN. Nunca se borra una página local con evidencia.
 *
 * No consulta la red. Devuelve [EvidenceClaim] con procedencia y confianza efectiva; el
 * marcado de inactividad por supersesión y los duplicados entre paquetes los resuelve luego
 * [SemanticClaimReducer.reduceMerged].
 */
class HybridClaimMerger(
    private val normalizer: SpanishNumberNormalizer = SpanishNumberNormalizer(),
) {

    fun merge(local: List<RawClaim>, remote: List<RawClaim>): List<EvidenceClaim> {
        val remoteCovered = remote.flatMap { coveredKeys(it) }.toSet()
        val hasRemote = remote.isNotEmpty()
        val localByKey = local.groupBy { key(it) }
        val matchedKeys = HashSet<String>()

        val out = ArrayList<EvidenceClaim>()

        remote.forEach { r ->
            val k = key(r)
            val matches = localByKey[k].orEmpty()
            if (matches.isNotEmpty()) {
                matchedKeys += k
                val effective = maxOf(r.effectiveConfidence, matches.maxOf { it.effectiveConfidence })
                out += r.toEvidenceClaim(
                    provenance = ClaimProvenance.BOTH,
                    effectiveConfidence = effective,
                    evidences = unionEvidences(r.evidences + matches.flatMap { it.evidences }),
                )
            } else {
                out += r.toEvidenceClaim(ClaimProvenance.REMOTE)
            }
        }

        local.forEach { l ->
            if (key(l) in matchedKeys) return@forEach
            val covered = hasRemote &&
                coveredKeys(l).isNotEmpty() &&
                coveredKeys(l).all { it in remoteCovered }
            if (covered) return@forEach
            out += l.toEvidenceClaim(ClaimProvenance.LOCAL)
        }

        return resolvePageConflicts(out)
    }

    /**
     * Una página solo remota que contradice a una página local con evidencia pasa a UNCERTAIN:
     * la lectura determinista local no se descarta, la remota queda para confirmación.
     */
    private fun resolvePageConflicts(claims: List<EvidenceClaim>): List<EvidenceClaim> {
        val groundedPages = claims
            .filter { it.category == ClaimCategory.PAGE && it.provenance != ClaimProvenance.REMOTE }
            .map { it.normalizedValue }
            .toSet()
        if (groundedPages.isEmpty()) return claims
        return claims.map { claim ->
            if (claim.category == ClaimCategory.PAGE &&
                claim.provenance == ClaimProvenance.REMOTE &&
                claim.normalizedValue !in groundedPages
            ) {
                claim.copy(status = ClaimStatus.UNCERTAIN)
            } else {
                claim
            }
        }
    }

    private fun key(claim: RawClaim): String = "${claim.category}|${coreValue(claim)}"

    private fun coreValue(claim: RawClaim): String {
        val kind = when (claim.category) {
            ClaimCategory.PAGE -> NumberKind.PAGE
            ClaimCategory.EXERCISE -> NumberKind.EXERCISE
            else -> null
        } ?: return claim.normalizedValue
        val numbers = normalizer.bareValues(claim.value, kind)
            .ifEmpty { normalizer.bareValues(claim.normalizedValue, kind) }
        return numbers.firstOrNull() ?: claim.normalizedValue
    }

    private fun coveredKeys(claim: RawClaim): List<String> =
        claim.evidences.map { "${it.blockId.value}|${it.startMs}|${it.endMs}" }

    private fun unionEvidences(evidences: List<EvidenceRef>): List<EvidenceRef> {
        val seen = HashSet<String>()
        return evidences.filter { seen.add("${it.blockId.value}|${it.startMs}|${it.endMs}") }
    }

    private fun RawClaim.toEvidenceClaim(
        provenance: ClaimProvenance,
        effectiveConfidence: Double = this.effectiveConfidence,
        evidences: List<EvidenceRef> = this.evidences,
    ): EvidenceClaim = EvidenceClaim(
        id = id,
        category = category,
        value = value,
        normalizedValue = normalizedValue,
        status = status,
        confidence = confidence,
        origin = origin,
        evidence = evidences.firstOrNull() ?: evidence,
        active = true,
        claimKey = claimKey,
        evidences = evidences,
        supersedesClaimKeys = supersedesClaimKeys,
        runId = runId,
        packetId = packetId,
        providerClaimKey = providerClaimKey,
        declaredConfidence = declaredConfidence,
        effectiveConfidence = effectiveConfidence,
        transcriptSpanIds = transcriptSpanIds,
        claimOrdinal = claimOrdinal,
        provenance = provenance,
    )
}

package com.capo.diarioclase.processing.evidence

class ClaimReducer {
    fun reduce(raw: List<RawClaim>): List<EvidenceClaim> {
        val last = raw.withIndex().associate { (index, claim) -> (claim.category to claim.normalizedValue) to index }
        return raw.mapIndexed { index, claim ->
            EvidenceClaim(
                id = claim.id,
                category = claim.category,
                value = claim.value,
                normalizedValue = claim.normalizedValue,
                status = claim.status,
                confidence = claim.confidence,
                origin = claim.origin,
                evidence = claim.evidence,
                active = last[claim.category to claim.normalizedValue] == index,
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

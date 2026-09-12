package com.capo.diarioclase.processing.evidence

class ClaimReducer {
    fun reduce(raw: List<RawClaim>): List<EvidenceClaim> {
        val last = raw.withIndex().associate { (index, claim) -> (claim.category to claim.normalizedValue) to index }
        return raw.mapIndexed { index, claim ->
            EvidenceClaim(claim.id, claim.category, claim.value, claim.normalizedValue, claim.status, claim.confidence, claim.origin, claim.evidence, last[claim.category to claim.normalizedValue] == index)
        }
    }
}

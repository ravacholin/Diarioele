package com.capo.diarioclase.processing.evidence

class InterpretationProjector {
    fun project(claims: List<EvidenceClaim>, mode: InterpretationMode): ClaimPresentation {
        val (acceptedAt, confirmAt) = when (mode) {
            InterpretationMode.CONSERVATIVE -> .85 to .60
            InterpretationMode.BALANCED -> .70 to .40
            InterpretationMode.EXHAUSTIVE -> .55 to .01
        }
        val accepted = mutableListOf<EvidenceClaim>()
        val confirm = mutableListOf<EvidenceClaim>()
        val hidden = mutableListOf<EvidenceClaim>()
        claims.forEach { claim ->
            val valid = claim.active && claim.evidence.excerpt.isNotBlank()
            when {
                !valid -> hidden += claim
                // El estado decide antes que la confianza.
                claim.status == ClaimStatus.UNCERTAIN -> confirm += claim
                claim.status == ClaimStatus.PROPOSED ||
                    claim.status == ClaimStatus.CANCELLED ||
                    claim.status == ClaimStatus.CORRECTED -> hidden += claim
                claim.confidence >= acceptedAt -> accepted += claim
                claim.confidence >= confirmAt -> confirm += claim
                else -> hidden += claim
            }
        }
        return ClaimPresentation(accepted, confirm, hidden)
    }
}

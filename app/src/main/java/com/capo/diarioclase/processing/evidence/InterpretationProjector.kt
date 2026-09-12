package com.capo.diarioclase.processing.evidence

class InterpretationProjector {
    fun project(claims: List<EvidenceClaim>, mode: InterpretationMode): ClaimPresentation {
        val (acceptedAt, confirmAt) = when (mode) {
            InterpretationMode.CONSERVATIVE -> .85 to .60
            InterpretationMode.BALANCED -> .70 to .40
            InterpretationMode.EXHAUSTIVE -> .55 to .01
        }
        val valid = claims.filter { it.active && it.evidence.excerpt.isNotBlank() }
        return ClaimPresentation(
            accepted = valid.filter { it.confidence >= acceptedAt },
            confirm = valid.filter { it.confidence in confirmAt..<acceptedAt },
            hidden = claims - valid.toSet() + valid.filter { it.confidence < confirmAt },
        )
    }
}

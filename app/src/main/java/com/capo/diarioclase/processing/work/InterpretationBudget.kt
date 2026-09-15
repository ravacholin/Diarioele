package com.capo.diarioclase.processing.work

import com.capo.diarioclase.processing.evidence.EvidenceClaim

/**
 * Presupuesto acotado de la interpretación semántica (Task I5).
 *
 * `packetMs` limita el trabajo remoto de un paquete y `sessionMs` el de la sesión completa.
 * Al agotarse cualquiera de los dos, el intérprete cae al resultado local sin propagar la
 * cancelación como falla de transcripción.
 */
data class InterpretationBudget(
    val packetMs: Long = 60_000,
    val sessionMs: Long = 120_000,
)

/**
 * Resultado terminal de la interpretación, independiente de [TranscriptionFailure].
 *
 * Nunca representa una falla de audio: aun cuando lo remoto no responde a tiempo o una
 * dependencia falla, se devuelve [LocalOrMixed] con los claims que se pudieron obtener
 * (locales o una mezcla) y el motivo semántico, si lo hubo.
 */
sealed interface InterpretationOutcome {
    val claims: List<EvidenceClaim>

    /** Toda la sesión se resolvió con respuestas remotas validadas. */
    data class Remote(override val claims: List<EvidenceClaim>) : InterpretationOutcome

    /** Al menos un paquete cayó al fallback local (deadline, sin red o dependencia caída). */
    data class LocalOrMixed(
        override val claims: List<EvidenceClaim>,
        val failure: InterpretationFailure?,
    ) : InterpretationOutcome
}

package com.capo.diarioclase.processing.work

import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.transcription.TranscriptSpan

/**
 * Interpretación semántica de una transcripción completa (Task 8).
 *
 * Es el punto de extensión que separa la interpretación del éxito de Whisper: el
 * coordinator la invoca solo después de completar la transcripción. La implementación de
 * producción ([com.capo.diarioclase.processing.semantic.RouterSemanticInterpreter]) arma
 * paquetes, los rutea por la cadena de proveedores gratuitos con fallback local, reduce y
 * devuelve claims listos para proyectar. Cuando no se inyecta un intérprete, el coordinator
 * conserva su extracción local determinista.
 */
interface SemanticInterpreter {
    suspend fun interpret(
        sessionId: SessionId,
        spans: List<TranscriptSpan>,
        budget: InterpretationBudget = InterpretationBudget(),
    ): InterpretationOutcome
}

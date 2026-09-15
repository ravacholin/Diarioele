package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import com.capo.diarioclase.processing.work.SemanticInterpreter

/**
 * Intérprete de producción (Task 8): arma paquetes contextuales, los rutea por la cadena
 * de proveedores gratuitos con fallback local, reduce y devuelve claims listos para
 * proyectar.
 *
 * - Procesa cada paquete por vez (nunca en paralelo) y comparte un circuito por ejecución
 *   entre paquetes, de modo que una autenticación o cuota deshabilita al proveedor para el
 *   resto de la sesión.
 * - Reutiliza la caché de respuestas validadas del router; por eso reabrir o cambiar de
 *   modo no vuelve a llamar a la red (el modo se aplica al proyectar, fuera de acá).
 * - Si no hay proveedores habilitados, todos los paquetes caen al fallback local.
 */
class RouterSemanticInterpreter(
    private val packetBuilder: InterpretationPacketBuilder,
    private val router: FreeInferenceRouter,
    private val reducer: SemanticClaimReducer,
    private val enabledProviders: suspend () -> List<ProviderModel>,
) : SemanticInterpreter {

    override suspend fun interpret(sessionId: SessionId, spans: List<TranscriptSpan>): List<EvidenceClaim> {
        val packets = packetBuilder.build(spans)
        val providers = enabledProviders()
        val disabled = mutableSetOf<InferenceProvider>()

        val rawClaims = packets.flatMap { packet ->
            when (val outcome = router.route(sessionId.value, packet, providers, disabled)) {
                is RoutedPacketOutcome.Remote -> outcome.claims
                is RoutedPacketOutcome.Local -> outcome.claims
            }
        }
        return reducer.reduce(rawClaims)
    }
}

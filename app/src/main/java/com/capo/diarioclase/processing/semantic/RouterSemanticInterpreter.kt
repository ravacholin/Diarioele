package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.RawClaim
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import com.capo.diarioclase.processing.work.InterpretationBudget
import com.capo.diarioclase.processing.work.InterpretationFailure
import com.capo.diarioclase.processing.work.InterpretationOutcome
import com.capo.diarioclase.processing.work.SemanticInterpreter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Intérprete de producción (Task 8, acotado por Task I5): arma paquetes contextuales, los
 * rutea por la cadena de proveedores gratuitos con fallback local, reduce y devuelve claims
 * listos para proyectar.
 *
 * Task I5 acota el tiempo total: el trabajo remoto vive dentro de `budget.sessionMs` y cada
 * paquete dentro de `budget.packetMs`. Al vencer cualquier límite —o al fallar una
 * dependencia (caché, credenciales)— el paquete cae al fallback local **sin** propagar la
 * cancelación como falla de transcripción. El resultado es siempre un [InterpretationOutcome]
 * terminal: nunca lanza por deadline y nunca deja la sesión en un estado fantasma.
 *
 * - Procesa cada paquete por vez (nunca en paralelo) y comparte, entre paquetes, el circuito
 *   por ejecución y el contador de fallas transitorias.
 * - Reutiliza la caché de respuestas validadas del router; por eso reabrir o cambiar de modo
 *   no vuelve a llamar a la red (el modo se aplica al proyectar, fuera de acá).
 * - Si no hay proveedores habilitados, todos los paquetes caen al fallback local.
 */
class RouterSemanticInterpreter(
    private val packetBuilder: InterpretationPacketBuilder,
    private val router: FreeInferenceRouter,
    private val reducer: SemanticClaimReducer,
    private val fallback: FallbackClaimExtractor,
    private val enabledProviders: suspend () -> List<ProviderModel>,
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
) : SemanticInterpreter {

    override suspend fun interpret(
        sessionId: SessionId,
        spans: List<TranscriptSpan>,
        budget: InterpretationBudget,
    ): InterpretationOutcome {
        val packets = packetBuilder.build(spans)
        if (packets.isEmpty()) return InterpretationOutcome.Remote(emptyList())

        val providers = try {
            enabledProviders()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }

        val runId = runIdFactory()
        val disabled = mutableSetOf<InferenceProvider>()
        val strikes = mutableMapOf<InferenceProvider, Int>()
        val raw = mutableListOf<RawClaim>()
        val processed = BooleanArray(packets.size)
        var anyLocal = false
        var failure: InterpretationFailure? = null

        val completedInBudget = withTimeoutOrNull(budget.sessionMs) {
            packets.forEachIndexed { index, packet ->
                var errored = false
                val outcome = try {
                    withTimeoutOrNull(budget.packetMs) {
                        router.route(
                            sessionId = sessionId.value,
                            packet = packet.request,
                            providers = providers,
                            disabledProviders = disabled,
                            runId = runId,
                            sourceSpanIds = packet.sourceSpanIds,
                            transientStrikes = strikes,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    errored = true
                    null
                }
                when (outcome) {
                    is RoutedPacketOutcome.Remote -> raw += outcome.claims
                    is RoutedPacketOutcome.Local -> {
                        raw += outcome.claims
                        anyLocal = true
                        failure = failure ?: outcome.failures.toInterpretationFailure()
                    }
                    null -> {
                        raw += fallback.extract(packet.request.spans)
                        anyLocal = true
                        failure = failure
                            ?: if (errored) InterpretationFailure.INTERNAL else InterpretationFailure.DEADLINE
                    }
                }
                processed[index] = true
            }
            true
        }

        if (completedInBudget == null) {
            anyLocal = true
            failure = failure ?: InterpretationFailure.DEADLINE
            packets.forEachIndexed { index, packet ->
                if (!processed[index]) raw += fallback.extract(packet.request.spans)
            }
        }

        val claims = reducer.reduce(raw)
        return if (anyLocal) {
            InterpretationOutcome.LocalOrMixed(claims, failure)
        } else {
            InterpretationOutcome.Remote(claims)
        }
    }

    private fun List<ProviderFailure>.toInterpretationFailure(): InterpretationFailure? = when {
        isEmpty() -> null
        any { it == ProviderFailure.NO_NETWORK || it == ProviderFailure.SERVER_UNAVAILABLE || it == ProviderFailure.TIMEOUT } ->
            InterpretationFailure.TRANSPORT
        any { it == ProviderFailure.INVALID_RESPONSE || it == ProviderFailure.EMPTY_RESPONSE } ->
            InterpretationFailure.INVALID_RESPONSE
        any { it == ProviderFailure.AUTHENTICATION || it == ProviderFailure.BILLING_RISK || it == ProviderFailure.QUOTA || it == ProviderFailure.NOT_CONFIGURED } ->
            InterpretationFailure.INTERNAL
        else -> InterpretationFailure.INTERNAL
    }
}

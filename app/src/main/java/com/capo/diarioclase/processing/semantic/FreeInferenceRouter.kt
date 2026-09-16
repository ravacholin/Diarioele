package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.evidence.RawClaim
import kotlinx.coroutines.delay

/**
 * Telemetría tipada de un intento contra un proveedor (Task I7b). No transporta cuerpos HTTP
 * ni credenciales: solo el resultado, el modelo, si fue acierto de caché y la duración.
 */
data class ProviderAttemptInfo(
    val provider: InferenceProvider,
    val modelId: String,
    val attempt: Int,
    val cacheHit: Boolean,
    val outcome: String,
    val durationMs: Long,
    val startedAtEpochMs: Long,
)

/** Resultado de rutear un paquete: remoto validado, o fallback local. */
sealed interface RoutedPacketOutcome {
    data class Remote(
        val claims: List<RawClaim>,
        val provider: InferenceProvider,
        val modelId: String,
    ) : RoutedPacketOutcome

    data class Local(
        val claims: List<RawClaim>,
        val failures: List<ProviderFailure>,
    ) : RoutedPacketOutcome
}

/**
 * Router secuencial de proveedores gratuitos (Task 7, acotado por Task I5).
 *
 * Compone clientes, validador, caché y fallback; **no** los redefine. Recorre los
 * proveedores habilitados en orden, uno por vez, y aplica la política de rutas: una
 * respuesta validada detiene la cadena; cuota/credencial/facturación cortan el proveedor;
 * `NO_NETWORK` va directo al fallback local; nunca hay llamadas paralelas ni proveedores
 * fuera del catálogo. La credencial se obtiene por proveedor alrededor de sus llamadas.
 *
 * Task I5 agrega: circuito por dos fallas transitorias consecutivas del mismo proveedor,
 * respeto de `retryAfterMs` solo cuando entra en el presupuesto restante, y asignación de
 * identidad global del claim (`ClaimIdentity`) más resolución de la evidencia pública a
 * spans de transcripción reales en el borde de validación.
 */
class FreeInferenceRouter(
    private val clients: Map<InferenceProvider, InferenceProviderClient>,
    private val validator: SemanticResponseValidator,
    private val fallback: FallbackClaimExtractor,
    private val retryPolicy: ProviderRetryPolicy = ProviderRetryPolicy(),
    private val cache: RoomInterpretationCache? = null,
    private val credentialFor: suspend (InferenceProvider) -> EphemeralCredential? = { null },
    private val onDelay: suspend (Long) -> Unit = { delay(it) },
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val consecutiveTransientToOpen: Int = 2,
) {

    suspend fun route(
        sessionId: String,
        packet: InterpretationRequest,
        providers: List<ProviderModel>,
        disabledProviders: MutableSet<InferenceProvider> = mutableSetOf(),
        runId: String = "",
        sourceSpanIds: Map<String, String> = emptyMap(),
        deadlineEpochMs: Long? = null,
        transientStrikes: MutableMap<InferenceProvider, Int> = mutableMapOf(),
        onAttempt: (suspend (ProviderAttemptInfo) -> Unit)? = null,
    ): RoutedPacketOutcome {
        cache?.find(sessionId, packet, providers)?.let { hit ->
            val cached = validator.validate(hit.validatedJson, hit.provider, packet.spans)
            if (cached is ValidationOutcome.Valid) {
                onAttempt?.invoke(
                    ProviderAttemptInfo(hit.provider, hit.modelId, 0, cacheHit = true, outcome = "REMOTE_OK", durationMs = 0, startedAtEpochMs = nowEpochMs()),
                )
                return RoutedPacketOutcome.Remote(
                    identify(cached.claims, runId, packet.packetId, hit.provider, sourceSpanIds),
                    hit.provider,
                    hit.modelId,
                )
            }
        }

        val failures = mutableListOf<ProviderFailure>()

        for ((provider, modelId) in providers) {
            if (provider in disabledProviders) continue
            val client = clients[provider]
            if (client == null) {
                failures += ProviderFailure.NOT_CONFIGURED
                continue
            }
            val credential = credentialFor(provider)
            if (credential == null) {
                failures += ProviderFailure.NOT_CONFIGURED
                continue
            }

            var requestsUsed = 0
            var goLocal = false
            while (requestsUsed < retryPolicy.maxRequestsPerProvider) {
                val startedAt = nowEpochMs()
                val outcome = client.infer(packet, credential)
                val durationMs = nowEpochMs() - startedAt
                requestsUsed++

                val failure: ProviderOutcome.Failure?
                val code = when (outcome) {
                    is ProviderOutcome.Success -> {
                        val validation = validator.validate(outcome.rawJson, provider, packet.spans)
                        if (validation is ValidationOutcome.Valid) {
                            cache?.store(sessionId, packet, provider, modelId, outcome.rawJson, nowEpochMs())
                            transientStrikes[provider] = 0
                            onAttempt?.invoke(
                                ProviderAttemptInfo(provider, modelId, requestsUsed, cacheHit = false, outcome = "REMOTE_OK", durationMs = durationMs, startedAtEpochMs = startedAt),
                            )
                            return RoutedPacketOutcome.Remote(
                                identify(validation.claims, runId, packet.packetId, provider, sourceSpanIds),
                                provider,
                                modelId,
                            )
                        }
                        failure = null
                        ProviderFailure.INVALID_RESPONSE
                    }

                    is ProviderOutcome.Failure -> {
                        failure = outcome
                        outcome.code
                    }
                }

                onAttempt?.invoke(
                    ProviderAttemptInfo(provider, modelId, requestsUsed, cacheHit = false, outcome = code.name, durationMs = durationMs, startedAtEpochMs = startedAt),
                )
                failures += code
                if (retryPolicy.opensCircuit(code)) disabledProviders += provider
                if (isTransient(code)) {
                    val strikes = (transientStrikes[provider] ?: 0) + 1
                    transientStrikes[provider] = strikes
                    if (strikes >= consecutiveTransientToOpen) {
                        disabledProviders += provider
                        break
                    }
                } else {
                    transientStrikes[provider] = 0
                }

                when (retryPolicy.decide(code, requestsUsed)) {
                    RetryDecision.RETRY -> {
                        val wait = failure?.retryAfterMs ?: retryPolicy.retryDelayMs
                        if (!waitFitsBudget(wait, deadlineEpochMs)) break
                        onDelay(wait)
                    }
                    RetryDecision.CORRECT -> Unit // solicitud correctiva inmediata
                    RetryDecision.STOP_PROVIDER -> break
                    RetryDecision.STOP_ALL -> {
                        goLocal = true
                        break
                    }
                }
            }
            if (goLocal) break
        }

        return RoutedPacketOutcome.Local(fallback.extract(packet.spans), failures.toList())
    }

    private fun isTransient(code: ProviderFailure): Boolean =
        code == ProviderFailure.SERVER_UNAVAILABLE || code == ProviderFailure.TIMEOUT

    private fun waitFitsBudget(waitMs: Long, deadlineEpochMs: Long?): Boolean =
        deadlineEpochMs == null || nowEpochMs() + waitMs <= deadlineEpochMs

    /**
     * Asigna la identidad global del claim en el borde de validación (Task I5): el `id` pasa a
     * ser [ClaimIdentity.id] sobre `runId+packetId+proveedor+claimKey`, las supersesiones se
     * reescriben al mismo espacio global y cada evidencia pública se resuelve a su span de
     * transcripción real vía [sourceSpanIds]. Con `runId` vacío (rutas legacy sin corrida) se
     * conserva el id local para no romper llamadas antiguas.
     */
    private fun identify(
        claims: List<RawClaim>,
        runId: String,
        packetId: String,
        provider: InferenceProvider,
        sourceSpanIds: Map<String, String>,
    ): List<RawClaim> {
        if (runId.isBlank() && sourceSpanIds.isEmpty()) return claims
        return claims.mapIndexed { index, claim ->
            val globalId =
                if (runId.isNotBlank()) ClaimIdentity.id(runId, packetId, provider, claim.claimKey) else claim.id
            val spanIds = claim.evidences.mapNotNull { evidence ->
                val block = evidence.blockOrdinal
                val span = evidence.spanOrdinal
                if (block != null && span != null) sourceSpanIds[PublicSpanId.of(block, span)] else null
            }
            claim.copy(
                id = globalId,
                claimKey = globalId,
                supersedesClaimKeys = claim.supersedesClaimKeys.map { key ->
                    if (runId.isNotBlank()) ClaimIdentity.id(runId, packetId, provider, key) else key
                },
                runId = runId.ifBlank { null },
                packetId = packetId,
                providerClaimKey = claim.claimKey,
                transcriptSpanIds = spanIds,
                claimOrdinal = index,
            )
        }
    }
}

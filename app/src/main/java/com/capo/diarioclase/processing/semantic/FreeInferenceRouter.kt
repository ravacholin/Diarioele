package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.evidence.RawClaim
import kotlinx.coroutines.delay

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
 * Router secuencial de proveedores gratuitos (Task 7).
 *
 * Compone clientes, validador, caché y fallback; **no** los redefine. Recorre los
 * proveedores habilitados en orden, uno por vez, y aplica la política de rutas: una
 * respuesta validada detiene la cadena; cuota/credencial/facturación cortan el proveedor;
 * `NO_NETWORK` va directo al fallback local; nunca hay llamadas paralelas ni proveedores
 * fuera del catálogo. La credencial se obtiene por proveedor alrededor de sus llamadas.
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
) {

    suspend fun route(
        sessionId: String,
        packet: InterpretationRequest,
        providers: List<ProviderModel>,
        disabledProviders: MutableSet<InferenceProvider> = mutableSetOf(),
    ): RoutedPacketOutcome {
        cache?.find(sessionId, packet, providers)?.let { hit ->
            val cached = validator.validate(hit.validatedJson, hit.provider, packet.spans)
            if (cached is ValidationOutcome.Valid) {
                return RoutedPacketOutcome.Remote(cached.claims, hit.provider, hit.modelId)
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
                val outcome = client.infer(packet, credential)
                requestsUsed++

                val code = when (outcome) {
                    is ProviderOutcome.Success -> {
                        val validation = validator.validate(outcome.rawJson, provider, packet.spans)
                        if (validation is ValidationOutcome.Valid) {
                            cache?.store(sessionId, packet, provider, modelId, outcome.rawJson, nowEpochMs())
                            return RoutedPacketOutcome.Remote(validation.claims, provider, modelId)
                        }
                        ProviderFailure.INVALID_RESPONSE
                    }

                    is ProviderOutcome.Failure -> outcome.code
                }

                failures += code
                if (retryPolicy.opensCircuit(code)) disabledProviders += provider
                when (retryPolicy.decide(code, requestsUsed)) {
                    RetryDecision.RETRY -> onDelay(retryPolicy.retryDelayMs)
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
}

package com.capo.diarioclase.processing.semantic

/**
 * Prueba de conexión de producción (Task 8): envía un paquete fijo mínimo al proveedor y
 * descarta la respuesta. No transcribe ni usa datos reales de la clase.
 */
class RealProviderConnectionTester(
    private val clients: Map<InferenceProvider, InferenceProviderClient>,
    private val credentials: ProviderCredentialStore,
    private val openRouterPreflight: OpenRouterBillingPreflight? = null,
) : ProviderConnectionTester {

    override suspend fun test(provider: InferenceProvider): ConnectionResult {
        val client = clients[provider] ?: return ConnectionResult.NOT_CONFIGURED
        val chars = credentials.readCredential(provider) ?: return ConnectionResult.NOT_CONFIGURED
        val credential = EphemeralCredential(String(chars))
        chars.fill(Char(0))

        // OpenRouter: preflight de facturación antes de la prueba de inferencia (Q2). La acción
        // “Probar conexión” siempre fuerza un refresco. Una clave con capacidad de gasto queda
        // deshabilitada sin llegar a enviar el paquete de prueba.
        if (provider == InferenceProvider.OPENROUTER && openRouterPreflight != null) {
            when (openRouterPreflight.inspect(credential, forceRefresh = true)) {
                OpenRouterBillingPreflight.Result.SAFE -> Unit
                OpenRouterBillingPreflight.Result.BILLING_WARNING -> return ConnectionResult.BILLING_WARNING
                OpenRouterBillingPreflight.Result.INVALID_KEY -> return ConnectionResult.INVALID_KEY
                OpenRouterBillingPreflight.Result.NO_NETWORK -> return ConnectionResult.NO_NETWORK
                OpenRouterBillingPreflight.Result.UNAVAILABLE -> return ConnectionResult.UNAVAILABLE
            }
        }

        val probe = InterpretationRequest(
            packetId = "connection-probe",
            promptVersion = "free-ele-v1",
            schemaVersion = "claims-v1",
            spans = listOf(
                PublicTranscriptSpan("B1-S1", 1, 1, 1, 0, 1, "Prueba de conexión.", contextOnly = false),
            ),
        )
        return when (val outcome = client.infer(probe, credential)) {
            is ProviderOutcome.Success -> ConnectionResult.OK
            is ProviderOutcome.Failure -> when (outcome.code) {
                ProviderFailure.AUTHENTICATION -> ConnectionResult.INVALID_KEY
                ProviderFailure.BILLING_RISK -> ConnectionResult.BILLING_WARNING
                ProviderFailure.QUOTA -> ConnectionResult.QUOTA
                ProviderFailure.NO_NETWORK -> ConnectionResult.NO_NETWORK
                // El proveedor respondió pero rechazó el pedido: típicamente un modelo
                // inexistente (404) o un formato inválido. Lo distinguimos para que el
                // usuario sepa que la clave está bien pero el modelo/petición no.
                ProviderFailure.INVALID_RESPONSE,
                ProviderFailure.EMPTY_RESPONSE -> ConnectionResult.MODEL_OR_REQUEST
                else -> ConnectionResult.UNAVAILABLE
            }
        }
    }
}

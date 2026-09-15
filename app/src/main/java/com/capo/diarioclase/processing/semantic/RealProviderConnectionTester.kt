package com.capo.diarioclase.processing.semantic

/**
 * Prueba de conexión de producción (Task 8): envía un paquete fijo mínimo al proveedor y
 * descarta la respuesta. No transcribe ni usa datos reales de la clase.
 */
class RealProviderConnectionTester(
    private val clients: Map<InferenceProvider, InferenceProviderClient>,
    private val credentials: ProviderCredentialStore,
) : ProviderConnectionTester {

    override suspend fun test(provider: InferenceProvider): ConnectionResult {
        val client = clients[provider] ?: return ConnectionResult.NOT_CONFIGURED
        val chars = credentials.readCredential(provider) ?: return ConnectionResult.NOT_CONFIGURED
        val credential = EphemeralCredential(String(chars))
        chars.fill(Char(0))

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

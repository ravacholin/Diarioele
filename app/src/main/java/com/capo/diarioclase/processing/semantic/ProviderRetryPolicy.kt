package com.capo.diarioclase.processing.semantic

/** Decisión del router ante una falla de proveedor. */
enum class RetryDecision {
    /** Reintentar el mismo proveedor tras una espera (fallas transitorias). */
    RETRY,

    /** Enviar una única solicitud correctiva al mismo proveedor (respuesta inválida). */
    CORRECT,

    /** Dejar de intentar este proveedor y pasar al siguiente. */
    STOP_PROVIDER,

    /** Abandonar la cadena remota y usar el fallback local (sin red). */
    STOP_ALL,
}

/**
 * Política de reintentos del router (Task 7).
 *
 * Reglas congeladas por el diseño:
 * - `SERVER_UNAVAILABLE` y `TIMEOUT` reintentan una vez tras [retryDelayMs].
 * - `INVALID_RESPONSE` y `EMPTY_RESPONSE` admiten una solicitud correctiva.
 * - `QUOTA`, `AUTHENTICATION`, `BILLING_RISK` cortan el proveedor y abren su circuito.
 * - `NO_NETWORK` va directo al fallback local.
 * - Máximo [maxRequestsPerProvider] requests por proveedor y paquete.
 */
class ProviderRetryPolicy(
    val retryDelayMs: Long = 2_000,
    val maxRequestsPerProvider: Int = 2,
) {

    fun decide(code: ProviderFailure, requestsUsed: Int): RetryDecision {
        if (code == ProviderFailure.NO_NETWORK) return RetryDecision.STOP_ALL
        if (opensCircuit(code)) return RetryDecision.STOP_PROVIDER
        if (requestsUsed >= maxRequestsPerProvider) return RetryDecision.STOP_PROVIDER
        return when (code) {
            ProviderFailure.SERVER_UNAVAILABLE, ProviderFailure.TIMEOUT -> RetryDecision.RETRY
            ProviderFailure.INVALID_RESPONSE, ProviderFailure.EMPTY_RESPONSE -> RetryDecision.CORRECT
            else -> RetryDecision.STOP_PROVIDER
        }
    }

    /** Estos códigos deshabilitan al proveedor durante el resto de la ejecución. */
    fun opensCircuit(code: ProviderFailure): Boolean = code in CIRCUIT_OPENING

    companion object {
        private val CIRCUIT_OPENING = setOf(
            ProviderFailure.AUTHENTICATION,
            ProviderFailure.BILLING_RISK,
            ProviderFailure.QUOTA,
            ProviderFailure.NO_NETWORK,
        )
    }
}

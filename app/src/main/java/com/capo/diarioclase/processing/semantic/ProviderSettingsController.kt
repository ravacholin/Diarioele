package com.capo.diarioclase.processing.semantic

/** Resultado de probar la conexión de un proveedor (sin transcribir). */
enum class ConnectionResult {
    OK,
    INVALID_KEY,
    BILLING_WARNING,
    QUOTA,
    NO_NETWORK,
    MODEL_OR_REQUEST,
    UNAVAILABLE,
    NOT_CONFIGURED,
}

/** Prueba de conexión inyectable (envía un prompt fijo mínimo y descarta la respuesta). */
fun interface ProviderConnectionTester {
    suspend fun test(provider: InferenceProvider): ConnectionResult
}

/**
 * Lógica de la configuración visible de proveedores (Task 8), separada de Compose para
 * poder probarla. Combina el catálogo gratuito, los ajustes (habilitado/consentimiento) y
 * el almacén cifrado de claves. Nunca expone la clave completa: solo los últimos cuatro
 * caracteres para reconocerla.
 */
class ProviderSettingsController(
    private val settings: ProviderSettingsStore,
    private val credentials: ProviderCredentialStore,
    private val connectionTester: ProviderConnectionTester = ProviderConnectionTester { ConnectionResult.UNAVAILABLE },
    private val consentVersion: String = CONSENT_VERSION,
) {

    data class ProviderView(
        val provider: InferenceProvider,
        val modelId: String,
        val enabled: Boolean,
        val consented: Boolean,
        val hasKey: Boolean,
        val keyLast4: String?,
    )

    fun providers(): List<ProviderView> = settings.profiles().map { profile ->
        ProviderView(
            provider = profile.provider,
            modelId = profile.modelId,
            enabled = profile.enabled,
            consented = profile.consentVersion != null,
            hasKey = credentials.hasCredential(profile.provider),
            keyLast4 = lastFour(profile.provider),
        )
    }

    fun setEnabled(provider: InferenceProvider, enabled: Boolean) =
        settings.setEnabled(provider, enabled)

    fun setConsent(provider: InferenceProvider, consented: Boolean) =
        settings.setConsentVersion(provider, if (consented) consentVersion else null)

    /** Guarda la clave cifrada y borra el buffer de entrada. */
    fun saveKey(provider: InferenceProvider, key: CharArray) {
        credentials.saveCredential(provider, key)
    }

    fun clearKey(provider: InferenceProvider) = credentials.clearCredential(provider)

    suspend fun testConnection(provider: InferenceProvider): ConnectionResult {
        if (!credentials.hasCredential(provider)) return ConnectionResult.NOT_CONFIGURED
        return connectionTester.test(provider)
    }

    private fun lastFour(provider: InferenceProvider): String? {
        val chars = credentials.readCredential(provider) ?: return null
        return try {
            if (chars.size <= 4) "*".repeat(chars.size) else String(chars.copyOfRange(chars.size - 4, chars.size))
        } finally {
            chars.fill(Char(0))
        }
    }

    companion object {
        const val CONSENT_VERSION = "consent-v1"
    }
}

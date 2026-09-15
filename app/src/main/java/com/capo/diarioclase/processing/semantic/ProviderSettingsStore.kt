package com.capo.diarioclase.processing.semantic

import android.content.Context

/**
 * Persiste las decisiones del usuario sobre los proveedores (Task 2): habilitado,
 * versión de consentimiento aceptada y orden efectivo de la cadena.
 *
 * **No** guarda secretos: las claves viven exclusivamente en [ProviderCredentialStore]
 * (Android Keystore). El `modelId` siempre proviene de [FreeProviderCatalog]; este store
 * no tiene ninguna forma de cambiarlo.
 */
class ProviderSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Perfiles combinando el catálogo con el estado guardado, en el orden efectivo. */
    fun profiles(): List<ProviderProfile> = order().map { provider ->
        FreeProviderCatalog.profile(provider).copy(
            enabled = isEnabled(provider),
            consentVersion = consentVersion(provider),
        )
    }

    /** Perfiles habilitados y con consentimiento vigente, en orden. */
    fun enabledProfilesInOrder(): List<ProviderProfile> =
        profiles().filter { it.enabled && it.consentVersion != null }

    fun isEnabled(provider: InferenceProvider): Boolean =
        prefs.getBoolean(enabledKey(provider), false)

    fun setEnabled(provider: InferenceProvider, enabled: Boolean) {
        prefs.edit().putBoolean(enabledKey(provider), enabled).apply()
    }

    fun consentVersion(provider: InferenceProvider): String? =
        prefs.getString(consentKey(provider), null)

    fun setConsentVersion(provider: InferenceProvider, consentVersion: String?) {
        prefs.edit().apply {
            if (consentVersion == null) remove(consentKey(provider))
            else putString(consentKey(provider), consentVersion)
        }.apply()
    }

    /** Orden efectivo; por defecto el del catálogo. Solo admite proveedores del catálogo. */
    fun order(): List<InferenceProvider> {
        val stored = prefs.getString(ORDER_KEY, null)
            ?: return FreeProviderCatalog.defaultOrder
        val parsed = stored.split(',')
            .mapNotNull { name -> runCatching { InferenceProvider.valueOf(name) }.getOrNull() }
            .distinct()
        // Completar con los que falten, conservando el orden del catálogo, para nunca perder proveedores.
        val missing = FreeProviderCatalog.defaultOrder.filter { it !in parsed }
        return parsed + missing
    }

    fun setOrder(order: List<InferenceProvider>) {
        val normalized = (order.distinct() + FreeProviderCatalog.defaultOrder.filter { it !in order })
        prefs.edit().putString(ORDER_KEY, normalized.joinToString(",") { it.name }).apply()
    }

    private fun enabledKey(provider: InferenceProvider) = "enabled.${provider.name}"
    private fun consentKey(provider: InferenceProvider) = "consent.${provider.name}"

    companion object {
        private const val PREFS_NAME = "provider-settings"
        private const val ORDER_KEY = "order"
    }
}

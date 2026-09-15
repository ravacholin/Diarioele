package com.capo.diarioclase.processing.semantic

/**
 * Catálogo cerrado de proveedores gratuitos (Task 2).
 *
 * Los `modelId` son constantes: no existe ningún método público para escribir otro
 * modelo, ni para agregar un proveedor fuera de este catálogo. OpenRouter queda fijado a
 * `openrouter/free`. El orden por defecto es Gemini, Groq, OpenRouter.
 *
 * Cada perfil arranca deshabilitado y sin consentimiento; el `ProviderSettingsStore`
 * guarda esas decisiones del usuario, nunca el catálogo mismo.
 */
object FreeProviderCatalog {

    val profiles: List<ProviderProfile> = listOf(
        ProviderProfile(
            provider = InferenceProvider.GEMINI,
            modelId = "gemini-2.5-flash",
            enabled = false,
            consentVersion = null,
        ),
        ProviderProfile(
            provider = InferenceProvider.GROQ,
            modelId = "openai/gpt-oss-20b",
            enabled = false,
            consentVersion = null,
        ),
        ProviderProfile(
            provider = InferenceProvider.OPENROUTER,
            modelId = "openrouter/free",
            enabled = false,
            consentVersion = null,
        ),
    )

    /** Orden por defecto de la cadena de inferencia. */
    val defaultOrder: List<InferenceProvider> = profiles.map { it.provider }

    fun modelId(provider: InferenceProvider): String =
        profiles.first { it.provider == provider }.modelId

    fun profile(provider: InferenceProvider): ProviderProfile =
        profiles.first { it.provider == provider }
}

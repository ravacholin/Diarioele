package com.capo.diarioclase.processing.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeProviderCatalogTest {

    @Test
    fun `catalog exposes exactly the three free providers in order`() {
        assertEquals(
            listOf(InferenceProvider.GEMINI, InferenceProvider.GROQ, InferenceProvider.OPENROUTER),
            FreeProviderCatalog.profiles.map { it.provider },
        )
        assertEquals(FreeProviderCatalog.defaultOrder, FreeProviderCatalog.profiles.map { it.provider })
    }

    @Test
    fun `model ids are fixed and openrouter is always free`() {
        assertEquals("gemini-2.5-flash", FreeProviderCatalog.modelId(InferenceProvider.GEMINI))
        assertEquals("openai/gpt-oss-20b", FreeProviderCatalog.modelId(InferenceProvider.GROQ))
        assertEquals("openrouter/free", FreeProviderCatalog.modelId(InferenceProvider.OPENROUTER))
        assertTrue(FreeProviderCatalog.modelId(InferenceProvider.OPENROUTER).endsWith("/free"))
    }

    @Test
    fun `every profile starts disabled without consent`() {
        FreeProviderCatalog.profiles.forEach { profile ->
            assertFalse(profile.enabled)
            assertNull(profile.consentVersion)
        }
    }

    @Test
    fun `there is no api to store a different model id`() {
        // El catálogo solo expone lecturas: `profiles` es inmutable y los modelId son
        // constantes. No hay setter ni forma de inyectar un modelId arbitrario.
        val methods = FreeProviderCatalog::class.java.methods.map { it.name }
        assertFalse(methods.any { it.startsWith("set") })
        // Confirmar estabilidad: dos lecturas devuelven el mismo modelId fijo.
        assertEquals(
            FreeProviderCatalog.modelId(InferenceProvider.GEMINI),
            FreeProviderCatalog.modelId(InferenceProvider.GEMINI),
        )
    }
}

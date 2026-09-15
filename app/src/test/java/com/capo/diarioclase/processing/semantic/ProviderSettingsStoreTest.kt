package com.capo.diarioclase.processing.semantic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderSettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults to catalog order with everything disabled`() {
        val store = ProviderSettingsStore(context)
        assertEquals(FreeProviderCatalog.defaultOrder, store.order())
        assertTrue(store.profiles().none { it.enabled })
        assertTrue(store.enabledProfilesInOrder().isEmpty())
    }

    @Test
    fun `persists enabled and consent per provider`() {
        val store = ProviderSettingsStore(context)
        store.setEnabled(InferenceProvider.GEMINI, true)
        store.setConsentVersion(InferenceProvider.GEMINI, "consent-v1")

        val reopened = ProviderSettingsStore(context)
        assertTrue(reopened.isEnabled(InferenceProvider.GEMINI))
        assertEquals("consent-v1", reopened.consentVersion(InferenceProvider.GEMINI))
        assertFalse(reopened.isEnabled(InferenceProvider.GROQ))
    }

    @Test
    fun `enabled profiles require both enabled and consent`() {
        val store = ProviderSettingsStore(context)
        store.setEnabled(InferenceProvider.GEMINI, true)
        // Sin consentimiento todavía: no entra en la cadena.
        assertTrue(store.enabledProfilesInOrder().isEmpty())

        store.setConsentVersion(InferenceProvider.GEMINI, "consent-v1")
        assertEquals(
            listOf(InferenceProvider.GEMINI),
            store.enabledProfilesInOrder().map { it.provider },
        )
    }

    @Test
    fun `model id always comes from the catalog`() {
        val store = ProviderSettingsStore(context)
        store.setEnabled(InferenceProvider.OPENROUTER, true)
        store.setConsentVersion(InferenceProvider.OPENROUTER, "consent-v1")
        val profile = store.profiles().first { it.provider == InferenceProvider.OPENROUTER }
        assertEquals("openrouter/free", profile.modelId)
    }

    @Test
    fun `clearing consent removes it`() {
        val store = ProviderSettingsStore(context)
        store.setConsentVersion(InferenceProvider.GROQ, "consent-v1")
        store.setConsentVersion(InferenceProvider.GROQ, null)
        assertNull(store.consentVersion(InferenceProvider.GROQ))
    }

    @Test
    fun `stored order keeps every catalog provider`() {
        val store = ProviderSettingsStore(context)
        store.setOrder(listOf(InferenceProvider.OPENROUTER))
        val order = store.order()
        assertEquals(InferenceProvider.OPENROUTER, order.first())
        assertEquals(FreeProviderCatalog.defaultOrder.toSet(), order.toSet())
    }
}

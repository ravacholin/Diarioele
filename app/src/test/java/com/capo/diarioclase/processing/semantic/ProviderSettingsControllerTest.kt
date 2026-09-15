package com.capo.diarioclase.processing.semantic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderSettingsControllerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var credentials: FakeCredentialStore
    private lateinit var controller: ProviderSettingsController

    @Before
    fun setUp() {
        credentials = FakeCredentialStore()
        controller = ProviderSettingsController(
            settings = ProviderSettingsStore(context),
            credentials = credentials,
            connectionTester = { ConnectionResult.OK },
        )
    }

    @Test
    fun `lists the three providers disabled without key or consent`() {
        val views = controller.providers()
        assertEquals(
            listOf(InferenceProvider.GEMINI, InferenceProvider.GROQ, InferenceProvider.OPENROUTER),
            views.map { it.provider },
        )
        assertTrue(views.none { it.enabled })
        assertTrue(views.none { it.consented })
        assertTrue(views.none { it.hasKey })
        assertEquals("openrouter/free", views.first { it.provider == InferenceProvider.OPENROUTER }.modelId)
    }

    @Test
    fun `enable and consent are reflected`() {
        controller.setEnabled(InferenceProvider.GEMINI, true)
        controller.setConsent(InferenceProvider.GEMINI, true)
        val view = controller.providers().first { it.provider == InferenceProvider.GEMINI }
        assertTrue(view.enabled)
        assertTrue(view.consented)
    }

    @Test
    fun `saving a key exposes only the last four characters and clearing removes it`() {
        controller.saveKey(InferenceProvider.GROQ, "gsk_secret_ABCD".toCharArray())
        val saved = controller.providers().first { it.provider == InferenceProvider.GROQ }
        assertTrue(saved.hasKey)
        assertEquals("ABCD", saved.keyLast4)

        controller.clearKey(InferenceProvider.GROQ)
        val cleared = controller.providers().first { it.provider == InferenceProvider.GROQ }
        assertFalse(cleared.hasKey)
        assertNull(cleared.keyLast4)
    }

    @Test
    fun `testing connection requires a saved key`() = runTest {
        assertEquals(ConnectionResult.NOT_CONFIGURED, controller.testConnection(InferenceProvider.GEMINI))
        controller.saveKey(InferenceProvider.GEMINI, "key".toCharArray())
        assertEquals(ConnectionResult.OK, controller.testConnection(InferenceProvider.GEMINI))
    }
}

private class FakeCredentialStore : ProviderCredentialStore {
    private val store = mutableMapOf<InferenceProvider, CharArray>()

    override fun hasCredential(provider: InferenceProvider) = store.containsKey(provider)

    override fun saveCredential(provider: InferenceProvider, value: CharArray) {
        store[provider] = value.copyOf()
        value.fill(Char(0))
    }

    override fun readCredential(provider: InferenceProvider): CharArray? = store[provider]?.copyOf()

    override fun clearCredential(provider: InferenceProvider) {
        store.remove(provider)
    }
}

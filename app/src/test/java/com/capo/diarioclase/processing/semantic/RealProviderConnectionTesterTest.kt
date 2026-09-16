package com.capo.diarioclase.processing.semantic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas de la prueba de conexión de producción (Q2), en particular el preflight de
 * facturación de OpenRouter (`GET /api/v1/key`). No usa red ni credenciales reales.
 */
class RealProviderConnectionTesterTest {

    private class FakeCredentialStore(private val keys: Map<InferenceProvider, String>) : ProviderCredentialStore {
        override fun hasCredential(provider: InferenceProvider): Boolean = provider in keys
        override fun saveCredential(provider: InferenceProvider, value: CharArray) = Unit
        override fun readCredential(provider: InferenceProvider): CharArray? = keys[provider]?.toCharArray()
        override fun clearCredential(provider: InferenceProvider) = Unit
    }

    private fun keyJson(isFreeTier: Boolean, limit: String): String =
        """{"data":{"label":"k","usage":0,"limit":$limit,"is_free_tier":$isFreeTier}}"""

    private fun successClient() = FakeInferenceProviderClient(
        InferenceProvider.OPENROUTER,
        FakeInferenceProviderClient.success(InferenceProvider.OPENROUTER, """{"claims":[]}"""),
    )

    private fun tester(
        transport: FakeInferenceHttpTransport,
        client: FakeInferenceProviderClient,
        key: String = "sk-or-v1-xxxx",
    ) = RealProviderConnectionTester(
        clients = mapOf(InferenceProvider.OPENROUTER to client),
        credentials = FakeCredentialStore(mapOf(InferenceProvider.OPENROUTER to key)),
        openRouterPreflight = OpenRouterBillingPreflight(transport, nowEpochMs = { 0 }),
    )

    @Test
    fun `a free-tier key with a bounded limit passes the preflight and probes inference`() = runTest {
        val transport = FakeInferenceHttpTransport.okGet(200, keyJson(isFreeTier = true, limit = "5.0"))
        val client = successClient()

        assertEquals(ConnectionResult.OK, tester(transport, client).test(InferenceProvider.OPENROUTER))
        assertEquals(1, client.attempts)
        assertEquals(1, transport.getRequests.size)
        assertEquals("Bearer sk-or-v1-xxxx", transport.lastGet!!.headers["Authorization"])
    }

    @Test
    fun `an unlimited key is a billing warning and never probes inference`() = runTest {
        val transport = FakeInferenceHttpTransport.okGet(200, keyJson(isFreeTier = false, limit = "null"))
        val client = successClient()

        assertEquals(ConnectionResult.BILLING_WARNING, tester(transport, client).test(InferenceProvider.OPENROUTER))
        assertEquals(0, client.attempts)
    }

    @Test
    fun `a key with spend capability is a billing warning`() = runTest {
        val transport = FakeInferenceHttpTransport.okGet(200, keyJson(isFreeTier = false, limit = "10.0"))
        val client = successClient()

        assertEquals(ConnectionResult.BILLING_WARNING, tester(transport, client).test(InferenceProvider.OPENROUTER))
        assertEquals(0, client.attempts)
    }

    @Test
    fun `an unauthorized preflight maps to invalid key`() = runTest {
        val transport = FakeInferenceHttpTransport.okGet(401, "")
        val client = successClient()

        assertEquals(ConnectionResult.INVALID_KEY, tester(transport, client).test(InferenceProvider.OPENROUTER))
        assertEquals(0, client.attempts)
    }

    @Test
    fun `gemini skips the preflight and uses the inference probe`() = runTest {
        val client = FakeInferenceProviderClient(
            InferenceProvider.GEMINI,
            FakeInferenceProviderClient.success(InferenceProvider.GEMINI, """{"claims":[]}"""),
        )
        val tester = RealProviderConnectionTester(
            clients = mapOf(InferenceProvider.GEMINI to client),
            credentials = FakeCredentialStore(mapOf(InferenceProvider.GEMINI to "gemini-key")),
            openRouterPreflight = OpenRouterBillingPreflight(FakeInferenceHttpTransport.throwingGet(IllegalStateException("no debe usarse"))),
        )

        assertEquals(ConnectionResult.OK, tester.test(InferenceProvider.GEMINI))
        assertEquals(1, client.attempts)
    }

    @Test
    fun `a safe preflight is cached and a forced refresh re-checks`() = runTest {
        val transport = FakeInferenceHttpTransport.okGet(200, keyJson(isFreeTier = true, limit = "5.0"))
        val preflight = OpenRouterBillingPreflight(transport, nowEpochMs = { 0 })
        val credential = EphemeralCredential("sk-or-v1-xxxx")

        assertEquals(OpenRouterBillingPreflight.Result.SAFE, preflight.inspect(credential, forceRefresh = true))
        assertEquals(OpenRouterBillingPreflight.Result.SAFE, preflight.inspect(credential, forceRefresh = false))
        assertEquals(1, transport.getRequests.size) // el segundo usó la caché

        assertEquals(OpenRouterBillingPreflight.Result.SAFE, preflight.inspect(credential, forceRefresh = true))
        assertEquals(2, transport.getRequests.size) // el refresco forzado vuelve a consultar
    }
}

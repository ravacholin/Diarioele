package com.capo.diarioclase.processing.semantic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FreeInferenceRouterTest {

    private val span = PublicTranscriptSpan("B1-S1", 1, 1, 1, 0, 1_000, "Vamos a la página cuarenta y dos.", contextOnly = false)
    private val packet = InterpretationRequest("p1", "free-ele-v1", "claims-v1", listOf(span))

    private val gemini = ProviderModel(InferenceProvider.GEMINI, "gemini-3-flash-preview")
    private val groq = ProviderModel(InferenceProvider.GROQ, "openai/gpt-oss-20b")
    private val openrouter = ProviderModel(InferenceProvider.OPENROUTER, "openrouter/free")
    private val chain = listOf(gemini, groq, openrouter)

    private val validJson = ProviderClaimsCodec.encode(
        listOf(ProviderSemanticClaim("B1-C1", "PAGE", "42", "42", "PERFORMED", 0.95, listOf("B1-S1"), emptyList())),
    )

    private fun router(
        clients: Map<InferenceProvider, FakeInferenceProviderClient>,
        credentialFor: suspend (InferenceProvider) -> EphemeralCredential? = { EphemeralCredential("k") },
    ) = FreeInferenceRouter(
        clients = clients,
        validator = SemanticResponseValidator(),
        fallback = FallbackClaimExtractor(),
        retryPolicy = ProviderRetryPolicy(retryDelayMs = 0),
        cache = null,
        credentialFor = credentialFor,
        onDelay = {},
        nowEpochMs = { 1 },
    ) to clients

    private fun fake(provider: InferenceProvider, vararg outcomes: ProviderOutcome) =
        FakeInferenceProviderClient(provider, outcomes.toList())

    @Test
    fun `gemini success stops the chain after one call`() = runTest {
        val g = fake(InferenceProvider.GEMINI, FakeInferenceProviderClient.success(InferenceProvider.GEMINI, validJson))
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.success(InferenceProvider.GROQ, validJson))
        val (r, _) = router(mapOf(InferenceProvider.GEMINI to g, InferenceProvider.GROQ to gr))

        val outcome = r.route("s", packet, chain)

        assertTrue(outcome is RoutedPacketOutcome.Remote)
        assertEquals(InferenceProvider.GEMINI, (outcome as RoutedPacketOutcome.Remote).provider)
        assertEquals(1, g.attempts)
        assertEquals(0, gr.attempts) // nunca se llama en paralelo ni de más
    }

    @Test
    fun `gemini quota falls through to groq`() = runTest {
        val g = fake(InferenceProvider.GEMINI, FakeInferenceProviderClient.quotaFailure(InferenceProvider.GEMINI))
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.success(InferenceProvider.GROQ, validJson))
        val (r, _) = router(mapOf(InferenceProvider.GEMINI to g, InferenceProvider.GROQ to gr))

        val outcome = r.route("s", packet, chain)

        assertEquals(InferenceProvider.GROQ, (outcome as RoutedPacketOutcome.Remote).provider)
        assertEquals(1, g.attempts)
        assertEquals(1, gr.attempts)
    }

    @Test
    fun `gemini retries a server error once then groq wins`() = runTest {
        val g = fake(
            InferenceProvider.GEMINI,
            FakeInferenceProviderClient.serverUnavailable(InferenceProvider.GEMINI),
            FakeInferenceProviderClient.serverUnavailable(InferenceProvider.GEMINI),
        )
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.success(InferenceProvider.GROQ, validJson))
        val (r, _) = router(mapOf(InferenceProvider.GEMINI to g, InferenceProvider.GROQ to gr))

        val outcome = r.route("s", packet, chain)

        assertEquals(InferenceProvider.GROQ, (outcome as RoutedPacketOutcome.Remote).provider)
        assertEquals(2, g.attempts)
    }

    @Test
    fun `gemini and groq quota then openrouter wins`() = runTest {
        val g = fake(InferenceProvider.GEMINI, FakeInferenceProviderClient.quotaFailure(InferenceProvider.GEMINI))
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.quotaFailure(InferenceProvider.GROQ))
        val or = fake(InferenceProvider.OPENROUTER, FakeInferenceProviderClient.success(InferenceProvider.OPENROUTER, validJson))
        val (r, _) = router(
            mapOf(
                InferenceProvider.GEMINI to g,
                InferenceProvider.GROQ to gr,
                InferenceProvider.OPENROUTER to or,
            ),
        )

        val outcome = r.route("s", packet, chain)

        assertEquals(InferenceProvider.OPENROUTER, (outcome as RoutedPacketOutcome.Remote).provider)
    }

    @Test
    fun `invalid response then invalid repair moves to the next provider`() = runTest {
        val g = fake(
            InferenceProvider.GEMINI,
            FakeInferenceProviderClient.invalidJson(InferenceProvider.GEMINI),
            FakeInferenceProviderClient.invalidJson(InferenceProvider.GEMINI),
        )
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.success(InferenceProvider.GROQ, validJson))
        val (r, _) = router(mapOf(InferenceProvider.GEMINI to g, InferenceProvider.GROQ to gr))

        val outcome = r.route("s", packet, chain)

        assertEquals(InferenceProvider.GROQ, (outcome as RoutedPacketOutcome.Remote).provider)
        assertEquals(2, g.attempts)
    }

    @Test
    fun `a missing credential skips the provider`() = runTest {
        val g = fake(InferenceProvider.GEMINI, FakeInferenceProviderClient.success(InferenceProvider.GEMINI, validJson))
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.success(InferenceProvider.GROQ, validJson))
        val (r, _) = router(
            mapOf(InferenceProvider.GEMINI to g, InferenceProvider.GROQ to gr),
            credentialFor = { provider -> if (provider == InferenceProvider.GEMINI) null else EphemeralCredential("k") },
        )

        val outcome = r.route("s", packet, chain)

        assertEquals(InferenceProvider.GROQ, (outcome as RoutedPacketOutcome.Remote).provider)
        assertEquals(0, g.attempts)
    }

    @Test
    fun `authentication disables the provider for the rest of the execution`() = runTest {
        val g = fake(InferenceProvider.GEMINI, FakeInferenceProviderClient.authenticationFailure(InferenceProvider.GEMINI))
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.success(InferenceProvider.GROQ, validJson))
        val (r, _) = router(mapOf(InferenceProvider.GEMINI to g, InferenceProvider.GROQ to gr))
        val disabled = mutableSetOf<InferenceProvider>()

        val first = r.route("s", packet, chain, disabled)
        val second = r.route("s", packet, chain, disabled)

        assertEquals(InferenceProvider.GROQ, (first as RoutedPacketOutcome.Remote).provider)
        assertEquals(InferenceProvider.GROQ, (second as RoutedPacketOutcome.Remote).provider)
        assertTrue(InferenceProvider.GEMINI in disabled)
        assertEquals(1, g.attempts) // no se vuelve a llamar tras deshabilitarse
    }

    @Test
    fun `all providers failing falls back to local`() = runTest {
        val g = fake(
            InferenceProvider.GEMINI,
            FakeInferenceProviderClient.serverUnavailable(InferenceProvider.GEMINI),
            FakeInferenceProviderClient.serverUnavailable(InferenceProvider.GEMINI),
        )
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.quotaFailure(InferenceProvider.GROQ))
        val or = fake(InferenceProvider.OPENROUTER, FakeInferenceProviderClient.quotaFailure(InferenceProvider.OPENROUTER))
        val (r, _) = router(
            mapOf(
                InferenceProvider.GEMINI to g,
                InferenceProvider.GROQ to gr,
                InferenceProvider.OPENROUTER to or,
            ),
        )

        val outcome = r.route("s", packet, chain)

        assertTrue(outcome is RoutedPacketOutcome.Local)
        val local = outcome as RoutedPacketOutcome.Local
        assertTrue(local.failures.contains(ProviderFailure.QUOTA))
        assertTrue(local.failures.contains(ProviderFailure.SERVER_UNAVAILABLE))
    }

    @Test
    fun `no network goes straight to local without trying later providers`() = runTest {
        val g = fake(InferenceProvider.GEMINI, FakeInferenceProviderClient.noNetwork(InferenceProvider.GEMINI))
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.success(InferenceProvider.GROQ, validJson))
        val (r, _) = router(mapOf(InferenceProvider.GEMINI to g, InferenceProvider.GROQ to gr))

        val outcome = r.route("s", packet, chain)

        assertTrue(outcome is RoutedPacketOutcome.Local)
        assertEquals(0, gr.attempts) // NO_NETWORK no intenta proveedores posteriores
    }

    @Test
    fun `two consecutive transient failures open the provider circuit`() = runTest {
        val g = fake(
            InferenceProvider.GEMINI,
            FakeInferenceProviderClient.serverUnavailable(InferenceProvider.GEMINI),
            FakeInferenceProviderClient.serverUnavailable(InferenceProvider.GEMINI),
        )
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.success(InferenceProvider.GROQ, validJson))
        val (r, _) = router(mapOf(InferenceProvider.GEMINI to g, InferenceProvider.GROQ to gr))
        val disabled = mutableSetOf<InferenceProvider>()
        val strikes = mutableMapOf<InferenceProvider, Int>()

        val first = r.route("s", packet, chain, disabled, transientStrikes = strikes)
        val second = r.route("s", packet, chain, disabled, transientStrikes = strikes)

        assertEquals(InferenceProvider.GROQ, (first as RoutedPacketOutcome.Remote).provider)
        assertEquals(InferenceProvider.GROQ, (second as RoutedPacketOutcome.Remote).provider)
        assertTrue(InferenceProvider.GEMINI in disabled)
        assertEquals(2, g.attempts) // dos strikes en la primera ruta; luego queda deshabilitado
    }

    @Test
    fun `a retry-after that exceeds the remaining budget skips the retry`() = runTest {
        val g = fake(
            InferenceProvider.GEMINI,
            ProviderOutcome.Failure(
                InferenceProvider.GEMINI,
                ProviderFailure.SERVER_UNAVAILABLE,
                retryable = true,
                retryAfterMs = 100_000L,
            ),
        )
        val gr = fake(InferenceProvider.GROQ, FakeInferenceProviderClient.success(InferenceProvider.GROQ, validJson))
        val (r, _) = router(mapOf(InferenceProvider.GEMINI to g, InferenceProvider.GROQ to gr))

        // nowEpochMs = 1; el retry-after (100 s) no entra antes del deadline (50 ms).
        val outcome = r.route("s", packet, chain, deadlineEpochMs = 50)

        assertEquals(InferenceProvider.GROQ, (outcome as RoutedPacketOutcome.Remote).provider)
        assertEquals(1, g.attempts) // no reintenta porque la espera no entra en el presupuesto
    }

    @Test
    fun `identity wiring assigns a global id and resolves transcript span ids`() = runTest {
        val g = fake(InferenceProvider.GEMINI, FakeInferenceProviderClient.success(InferenceProvider.GEMINI, validJson))
        val (r, _) = router(mapOf(InferenceProvider.GEMINI to g))
        val sources = mapOf("B1-S1" to "transcript-span-1")

        val outcome = r.route("s", packet, chain, runId = "run-1", sourceSpanIds = sources)

        val claim = (outcome as RoutedPacketOutcome.Remote).claims.single()
        assertEquals(ClaimIdentity.id("run-1", packet.packetId, InferenceProvider.GEMINI, "B1-C1"), claim.id)
        assertEquals(claim.id, claim.claimKey)
        assertEquals("B1-C1", claim.providerClaimKey)
        assertEquals("run-1", claim.runId)
        assertEquals(packet.packetId, claim.packetId)
        assertEquals(listOf("transcript-span-1"), claim.transcriptSpanIds)
    }
}

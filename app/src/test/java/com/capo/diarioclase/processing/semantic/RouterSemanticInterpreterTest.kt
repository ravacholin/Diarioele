package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouterSemanticInterpreterTest {

    private val spans = listOf(
        TranscriptSpan("s1", "seg", BlockId("blk"), 0, 3_000, "Vamos a la página cuarenta y dos", 0.9),
    )

    private fun interpreter(
        clients: Map<InferenceProvider, FakeInferenceProviderClient> = emptyMap(),
        providers: List<ProviderModel> = emptyList(),
    ) = RouterSemanticInterpreter(
        packetBuilder = InterpretationPacketBuilder(),
        router = FreeInferenceRouter(
            clients = clients,
            validator = SemanticResponseValidator(),
            fallback = FallbackClaimExtractor(),
            retryPolicy = ProviderRetryPolicy(retryDelayMs = 0),
            cache = null,
            credentialFor = { EphemeralCredential("k") },
            onDelay = {},
            nowEpochMs = { 1 },
        ),
        reducer = SemanticClaimReducer(),
        enabledProviders = { providers },
    )

    @Test
    fun `without providers it falls back to local extraction`() = runTest {
        val claims = interpreter().interpret(SessionId("s"), spans)
        assertTrue(claims.any { it.category == ClaimCategory.PAGE && it.value == "42" })
        assertTrue(claims.all { it.origin == ClaimOrigin.LOCAL_RULE })
    }

    @Test
    fun `a valid gemini response yields remote claims`() = runTest {
        val validJson = ProviderClaimsCodec.encode(
            listOf(ProviderSemanticClaim("B1-C1", "PAGE", "42", "42", "PERFORMED", 0.95, listOf("B1-S1"), emptyList())),
        )
        val gemini = FakeInferenceProviderClient(
            InferenceProvider.GEMINI,
            FakeInferenceProviderClient.success(InferenceProvider.GEMINI, validJson),
        )

        val claims = interpreter(
            clients = mapOf(InferenceProvider.GEMINI to gemini),
            providers = listOf(ProviderModel(InferenceProvider.GEMINI, "gemini-3-flash-preview")),
        ).interpret(SessionId("s"), spans)

        assertTrue(claims.any { it.category == ClaimCategory.PAGE && it.value == "42" && it.origin == ClaimOrigin.GEMINI })
    }
}

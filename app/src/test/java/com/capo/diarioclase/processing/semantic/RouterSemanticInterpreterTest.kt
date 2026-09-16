package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import com.capo.diarioclase.processing.work.InterpretationBudget
import com.capo.diarioclase.processing.work.InterpretationFailure
import com.capo.diarioclase.processing.work.InterpretationJournal
import com.capo.diarioclase.processing.work.InterpretationOutcome
import com.capo.diarioclase.processing.work.InterpretationPacketState
import com.capo.diarioclase.processing.work.InterpretationRunRecord
import com.capo.diarioclase.processing.work.InterpretationRunState
import com.capo.diarioclase.processing.work.PersistedClaim
import com.capo.diarioclase.processing.work.ProviderAttemptRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        fallback = FallbackClaimExtractor(),
        enabledProviders = { providers },
    )

    @Test
    fun `without providers it falls back to local extraction`() = runTest {
        val claims = interpreter().interpret(SessionId("s"), spans).claims
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
        ).interpret(SessionId("s"), spans).claims

        assertTrue(claims.any { it.category == ClaimCategory.PAGE && it.value == "42" && it.origin == ClaimOrigin.GEMINI })
    }

    private val validJson = ProviderClaimsCodec.encode(
        listOf(ProviderSemanticClaim("B1-C1", "PAGE", "42", "42", "PERFORMED", 0.95, listOf("B1-S1"), emptyList())),
    )

    private fun interpreterWith(
        clients: Map<InferenceProvider, InferenceProviderClient>,
        credentialFor: suspend (InferenceProvider) -> EphemeralCredential? = { EphemeralCredential("k") },
    ) = RouterSemanticInterpreter(
        packetBuilder = InterpretationPacketBuilder(),
        router = FreeInferenceRouter(
            clients = clients,
            validator = SemanticResponseValidator(),
            fallback = FallbackClaimExtractor(),
            retryPolicy = ProviderRetryPolicy(retryDelayMs = 0),
            cache = null,
            credentialFor = credentialFor,
            onDelay = {},
            nowEpochMs = { 1 },
        ),
        reducer = SemanticClaimReducer(),
        fallback = FallbackClaimExtractor(),
        enabledProviders = { listOf(ProviderModel(InferenceProvider.GEMINI, "gemini-3-flash-preview")) },
        runIdFactory = { "run-1" },
    )

    @Test
    fun `session deadline falls back locally without throwing and stays within budget`() = runTest {
        // Un proveedor que nunca responde dentro del presupuesto: el paquete se cancela y cae
        // al fallback local, sin propagar la cancelación como falla de transcripción.
        val slow = object : InferenceProviderClient {
            override suspend fun infer(
                request: InterpretationRequest,
                credential: EphemeralCredential,
                attempt: InferenceAttemptContext,
            ): ProviderOutcome {
                delay(10 * 60_000L)
                return FakeInferenceProviderClient.success(InferenceProvider.GEMINI, validJson)
            }
        }

        val outcome = interpreterWith(mapOf(InferenceProvider.GEMINI to slow))
            .interpret(SessionId("s"), spans, InterpretationBudget(packetMs = 60_000, sessionMs = 120_000))

        assertTrue(outcome is InterpretationOutcome.LocalOrMixed)
        outcome as InterpretationOutcome.LocalOrMixed
        assertEquals(InterpretationFailure.DEADLINE, outcome.failure)
        assertTrue(outcome.claims.any { it.category == ClaimCategory.PAGE && it.value == "42" })
        assertTrue(outcome.claims.all { it.origin == ClaimOrigin.LOCAL_RULE })
        assertTrue(testScheduler.currentTime <= 120_000)
    }

    private class RecordingJournal : InterpretationJournal {
        var begun: InterpretationRunRecord? = null
        var completedState: InterpretationRunState? = null
        val packets = mutableListOf<String>()
        val completedPackets = mutableListOf<InterpretationPacketState>()
        val attempts = mutableListOf<ProviderAttemptRecord>()
        override suspend fun beginRun(run: InterpretationRunRecord) { begun = run }
        override suspend fun startPacket(runId: String, packetId: String, ordinal: Int, requestHash: String, requestBytes: Int) { packets += packetId }
        override suspend fun recordAttempt(attempt: ProviderAttemptRecord) { attempts += attempt }
        override suspend fun completePacket(runId: String, packetId: String, state: InterpretationPacketState, provider: InferenceProvider?) { completedPackets += state }
        override suspend fun failRun(runId: String, failure: InterpretationFailure) {}
        override suspend fun completeRun(runId: String, state: InterpretationRunState) { completedState = state }
        override suspend fun saveClaims(claims: List<PersistedClaim>) {}
        override suspend fun loadClaims(runId: String): List<PersistedClaim> = emptyList()
    }

    @Test
    fun `records run packets and attempts in the journal`() = runTest {
        val journal = RecordingJournal()
        val gemini = FakeInferenceProviderClient(
            InferenceProvider.GEMINI,
            FakeInferenceProviderClient.success(InferenceProvider.GEMINI, validJson),
        )
        val interp = RouterSemanticInterpreter(
            packetBuilder = InterpretationPacketBuilder(),
            router = FreeInferenceRouter(
                clients = mapOf(InferenceProvider.GEMINI to gemini),
                validator = SemanticResponseValidator(),
                fallback = FallbackClaimExtractor(),
                retryPolicy = ProviderRetryPolicy(retryDelayMs = 0),
                cache = null,
                credentialFor = { EphemeralCredential("k") },
                onDelay = {},
                nowEpochMs = { 5 },
            ),
            reducer = SemanticClaimReducer(),
            fallback = FallbackClaimExtractor(),
            enabledProviders = { listOf(ProviderModel(InferenceProvider.GEMINI, "gemini-3-flash-preview")) },
            runIdFactory = { "run-1" },
            journal = journal,
            nowEpochMs = { 5 },
        )

        interp.interpret(SessionId("s"), spans, InterpretationBudget(60_000, 120_000))

        assertEquals("run-1", journal.begun?.id)
        assertEquals(InterpretationRunState.REMOTE_OK, journal.completedState)
        assertEquals(1, journal.packets.size)
        assertEquals(listOf(InterpretationPacketState.REMOTE_OK), journal.completedPackets)
        assertTrue(journal.attempts.any { it.outcome == "REMOTE_OK" && it.provider == InferenceProvider.GEMINI })
    }

    @Test
    fun `a failing dependency produces a local result not a crash`() = runTest {
        val gemini = FakeInferenceProviderClient(
            InferenceProvider.GEMINI,
            FakeInferenceProviderClient.success(InferenceProvider.GEMINI, validJson),
        )

        val outcome = interpreterWith(
            clients = mapOf(InferenceProvider.GEMINI to gemini),
            credentialFor = { throw IllegalStateException("credential store down") },
        ).interpret(SessionId("s"), spans, InterpretationBudget(packetMs = 60_000, sessionMs = 120_000))

        assertTrue(outcome is InterpretationOutcome.LocalOrMixed)
        outcome as InterpretationOutcome.LocalOrMixed
        assertEquals(InterpretationFailure.INTERNAL, outcome.failure)
        assertTrue(outcome.claims.any { it.category == ClaimCategory.PAGE && it.value == "42" })
    }
}

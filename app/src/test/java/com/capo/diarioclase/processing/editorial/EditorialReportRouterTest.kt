package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.InferenceProvider
import com.capo.diarioclase.processing.semantic.ProviderFailure
import com.capo.diarioclase.processing.semantic.ProviderModel
import com.capo.diarioclase.processing.semantic.ProviderOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialReportRouterTest {
    @Test
    fun `incomplete response is repaired once before next provider`() = runTest {
        val attempts = mutableListOf<String>()
        val client = QueueClient(
            InferenceProvider.GEMINI,
            listOf(success(emptyReport), success(validReport)),
            attempts,
        )
        val router = router(mapOf(InferenceProvider.GEMINI to client))

        val result = router.route(request(), listOf(ProviderModel(InferenceProvider.GEMINI, "gemini-2.5-flash")))

        assertTrue(result is EditorialRoute.Ready)
        assertEquals(listOf("GEMINI:initial", "GEMINI:repair"), attempts)
    }

    @Test
    fun `failed repair advances to next free provider`() = runTest {
        val attempts = mutableListOf<String>()
        val gemini = QueueClient(
            InferenceProvider.GEMINI,
            listOf(success(emptyReport), success(emptyReport)),
            attempts,
        )
        val groq = QueueClient(InferenceProvider.GROQ, listOf(success(validReport)), attempts)
        val router = router(mapOf(InferenceProvider.GEMINI to gemini, InferenceProvider.GROQ to groq))

        val result = router.route(
            request(),
            listOf(
                ProviderModel(InferenceProvider.GEMINI, "gemini-2.5-flash"),
                ProviderModel(InferenceProvider.GROQ, "openai/gpt-oss-20b"),
            ),
        )

        assertTrue(result is EditorialRoute.Ready)
        assertEquals(listOf("GEMINI:initial", "GEMINI:repair", "GROQ:initial"), attempts)
    }

    @Test
    fun `no network stops the provider chain`() = runTest {
        val attempts = mutableListOf<String>()
        val gemini = QueueClient(
            InferenceProvider.GEMINI,
            listOf(ProviderOutcome.Failure(InferenceProvider.GEMINI, ProviderFailure.NO_NETWORK, false)),
            attempts,
        )
        val groq = QueueClient(InferenceProvider.GROQ, listOf(success(validReport)), attempts)
        val router = router(mapOf(InferenceProvider.GEMINI to gemini, InferenceProvider.GROQ to groq))

        val result = router.route(
            request(),
            listOf(ProviderModel(InferenceProvider.GEMINI, "g"), ProviderModel(InferenceProvider.GROQ, "q")),
        )

        assertTrue(result is EditorialRoute.Unavailable)
        assertEquals(listOf("GEMINI:initial"), attempts)
    }

    private fun router(clients: Map<InferenceProvider, EditorialProviderClient>) = EditorialReportRouter(
        clients = clients,
        validator = EditorialReportValidator(),
        credentialFor = { EphemeralCredential("secret") },
    )

    private fun request() = EditorialReportRequest(
        sessionId = "private-session",
        inputHash = "hash",
        items = listOf(GeminiEditorialProviderClientTest.pageItem()),
    )

    private fun success(json: String) = ProviderOutcome.Success(InferenceProvider.GEMINI, "model", json)

    private class QueueClient(
        private val provider: InferenceProvider,
        outcomes: List<ProviderOutcome>,
        private val attempts: MutableList<String>,
    ) : EditorialProviderClient {
        private val queue = ArrayDeque(outcomes)

        override suspend fun generate(
            request: EditorialReportRequest,
            credential: EphemeralCredential,
            repair: EditorialRepair?,
        ): ProviderOutcome {
            attempts += "${provider.name}:${if (repair == null) "initial" else "repair"}"
            return queue.removeFirst()
        }
    }

    private companion object {
        const val emptyReport =
            "{\"summary\":\"\",\"material\":[],\"homework\":[],\"summary_source_claim_ids\":[],\"discarded\":[]}"
        const val validReport = GeminiEditorialProviderClientTest.validReport
    }
}

package com.capo.diarioclase.processing.semantic

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleProviderClientTest {

    private fun request() = InterpretationRequest(
        packetId = "p1",
        promptVersion = "free-ele-v1",
        schemaVersion = "claims-v1",
        spans = listOf(
            PublicTranscriptSpan("B1-S1", 1, 1, 1, 0, 1_000, "Vamos a la página cuarenta.", contextOnly = false),
        ),
    )

    private fun openAiEnvelope(content: String): String = buildJsonObject {
        putJsonArray("choices") {
            add(buildJsonObject { putJsonObject("message") { put("content", content) } })
        }
    }.toString()

    @Test
    fun `groq success extracts content and sends strict json schema with bearer`() = runTest {
        val claims = """{"claims":[]}"""
        val transport = FakeInferenceHttpTransport.ok(200, openAiEnvelope(claims))
        val client = OpenAiCompatibleProviderClient(OpenAiCompatibleProfile.GROQ, transport)

        val outcome = client.infer(request(), EphemeralCredential("secret-key"))

        assertTrue(outcome is ProviderOutcome.Success)
        assertEquals(claims, (outcome as ProviderOutcome.Success).rawJson)
        assertEquals(InferenceProvider.GROQ, outcome.provider)
        val recorded = transport.lastRequest!!
        assertEquals("Bearer secret-key", recorded.headers["Authorization"])
        assertEquals("https://api.groq.com/openai/v1/chat/completions", recorded.url)
        assertTrue(recorded.body.contains("\"model\":\"openai/gpt-oss-20b\""))
        assertTrue(recorded.body.contains("\"json_schema\""))
        assertTrue(recorded.body.contains("\"strict\":true"))
    }

    @Test
    fun `openrouter uses json object and denies data collection`() = runTest {
        val transport = FakeInferenceHttpTransport.ok(200, openAiEnvelope("""{"claims":[]}"""))
        val client = OpenAiCompatibleProviderClient(OpenAiCompatibleProfile.OPENROUTER, transport)

        val outcome = client.infer(request(), EphemeralCredential("k"))

        assertTrue(outcome is ProviderOutcome.Success)
        val body = transport.lastRequest!!.body
        assertTrue(body.contains("\"model\":\"openrouter/free\""))
        assertTrue(body.contains("\"json_object\""))
        assertTrue(body.contains("\"data_collection\":\"deny\""))
    }

    @Test
    fun `billing risk and quota map from status`() = runTest {
        val billing = OpenAiCompatibleProviderClient(
            OpenAiCompatibleProfile.OPENROUTER,
            FakeInferenceHttpTransport.ok(402, ""),
        ).infer(request(), EphemeralCredential("k"))
        assertEquals(ProviderFailure.BILLING_RISK, (billing as ProviderOutcome.Failure).code)

        val quota = OpenAiCompatibleProviderClient(
            OpenAiCompatibleProfile.GROQ,
            FakeInferenceHttpTransport.ok(429, ""),
        ).infer(request(), EphemeralCredential("k"))
        assertEquals(ProviderFailure.QUOTA, (quota as ProviderOutcome.Failure).code)
    }

    @Test
    fun `timeout from transport maps to timeout`() = runTest {
        val transport = FakeInferenceHttpTransport.throwing(java.net.SocketTimeoutException("slow"))
        val outcome = OpenAiCompatibleProviderClient(OpenAiCompatibleProfile.GROQ, transport)
            .infer(request(), EphemeralCredential("k"))
        assertEquals(ProviderFailure.TIMEOUT, (outcome as ProviderOutcome.Failure).code)
    }

    @Test
    fun `unparseable body is an invalid response`() = runTest {
        val outcome = OpenAiCompatibleProviderClient(
            OpenAiCompatibleProfile.GROQ,
            FakeInferenceHttpTransport.ok(200, """{"nope":true}"""),
        ).infer(request(), EphemeralCredential("k"))
        assertEquals(ProviderFailure.INVALID_RESPONSE, (outcome as ProviderOutcome.Failure).code)
    }
}

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

class GeminiProviderClientTest {

    private fun request() = InterpretationRequest(
        packetId = "p1",
        promptVersion = "free-ele-v1",
        schemaVersion = "claims-v1",
        spans = listOf(
            PublicTranscriptSpan("B1-S1", 1, 1, 1, 0, 1_000, "Vamos a la página cuarenta.", contextOnly = false),
        ),
    )

    private fun geminiEnvelope(text: String): String = buildJsonObject {
        putJsonArray("candidates") {
            add(
                buildJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    }
                },
            )
        }
    }.toString()

    @Test
    fun `success extracts the candidate json and sends the api key header`() = runTest {
        val claims = """{"claims":[]}"""
        val transport = FakeInferenceHttpTransport.ok(200, geminiEnvelope(claims))
        val client = GeminiProviderClient(transport)

        val outcome = client.infer(request(), EphemeralCredential("secret-key"))

        assertTrue(outcome is ProviderOutcome.Success)
        assertEquals(claims, (outcome as ProviderOutcome.Success).rawJson)
        val recorded = transport.lastRequest!!
        assertEquals("secret-key", recorded.headers["x-goog-api-key"])
        assertTrue(recorded.url.startsWith("https://generativelanguage.googleapis.com/"))
        // La clave nunca viaja en la URL.
        assertTrue(!recorded.url.contains("secret-key"))
    }

    @Test
    fun `body sends the native json schema and a repair prompt when repairing`() = runTest {
        val transport = FakeInferenceHttpTransport.ok(200, geminiEnvelope("""{"claims":[]}"""))
        val client = GeminiProviderClient(transport)

        client.infer(
            request(),
            EphemeralCredential("k"),
            InferenceAttemptContext.repair(setOf(SemanticIssue.NUMERIC_EVIDENCE_MISMATCH)),
        )

        val body = transport.lastRequest!!.body
        // Esquema estructurado nativo de Gemini.
        assertTrue(body.contains("responseJsonSchema"))
        assertTrue(body.contains("supersedes_claim_keys"))
        // El prompt de reparación viaja con el código de issue, sin cuerpo privado.
        assertTrue(body.contains("NUMERIC_EVIDENCE_MISMATCH"))
    }

    @Test
    fun `maps quota and reads retry after`() = runTest {
        val transport = FakeInferenceHttpTransport.ok(429, "", mapOf("Retry-After" to "2"))
        val outcome = GeminiProviderClient(transport).infer(request(), EphemeralCredential("k"))
        assertTrue(outcome is ProviderOutcome.Failure)
        val failure = outcome as ProviderOutcome.Failure
        assertEquals(ProviderFailure.QUOTA, failure.code)
        assertEquals(2_000L, failure.retryAfterMs)
    }

    @Test
    fun `maps authentication and server errors`() = runTest {
        val auth = GeminiProviderClient(FakeInferenceHttpTransport.ok(401, ""))
            .infer(request(), EphemeralCredential("k"))
        assertEquals(ProviderFailure.AUTHENTICATION, (auth as ProviderOutcome.Failure).code)

        val server = GeminiProviderClient(FakeInferenceHttpTransport.ok(503, ""))
            .infer(request(), EphemeralCredential("k"))
        val serverFailure = server as ProviderOutcome.Failure
        assertEquals(ProviderFailure.SERVER_UNAVAILABLE, serverFailure.code)
        assertTrue(serverFailure.retryable)
    }

    @Test
    fun `empty body is an empty response`() = runTest {
        val outcome = GeminiProviderClient(FakeInferenceHttpTransport.ok(200, ""))
            .infer(request(), EphemeralCredential("k"))
        assertEquals(ProviderFailure.EMPTY_RESPONSE, (outcome as ProviderOutcome.Failure).code)
    }

    @Test
    fun `unparseable candidate is an invalid response`() = runTest {
        val outcome = GeminiProviderClient(FakeInferenceHttpTransport.ok(200, """{"nope":true}"""))
            .infer(request(), EphemeralCredential("k"))
        assertEquals(ProviderFailure.INVALID_RESPONSE, (outcome as ProviderOutcome.Failure).code)
    }

    @Test
    fun `timeout from transport maps to timeout failure`() = runTest {
        val transport = FakeInferenceHttpTransport.throwing(java.net.SocketTimeoutException("slow"))
        val outcome = GeminiProviderClient(transport).infer(request(), EphemeralCredential("k"))
        val failure = outcome as ProviderOutcome.Failure
        assertEquals(ProviderFailure.TIMEOUT, failure.code)
        assertTrue(failure.retryable)
    }
}

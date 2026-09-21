package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.FakeInferenceHttpTransport
import com.capo.diarioclase.processing.semantic.OpenAiCompatibleProfile
import com.capo.diarioclase.processing.semantic.ProviderOutcome
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiEditorialProviderClientTest {
    @Test
    fun `groq sends strict schema and fixed free model`() = runTest {
        val transport = FakeInferenceHttpTransport.ok(200, envelope(GeminiEditorialProviderClientTest.validReport))
        val client = OpenAiEditorialProviderClient(OpenAiCompatibleProfile.GROQ, transport)

        val outcome = client.generate(request(), EphemeralCredential("secret"))

        assertTrue(outcome is ProviderOutcome.Success)
        val recorded = transport.lastRequest!!
        assertEquals("Bearer secret", recorded.headers["Authorization"])
        assertTrue(recorded.body.contains("\"model\":\"openai/gpt-oss-20b\""))
        assertTrue(recorded.body.contains("\"json_schema\""))
        assertTrue(recorded.body.contains("\"strict\":true"))
    }

    @Test
    fun `openrouter remains free and denies data collection`() = runTest {
        val transport = FakeInferenceHttpTransport.ok(200, envelope(GeminiEditorialProviderClientTest.validReport))
        val client = OpenAiEditorialProviderClient(OpenAiCompatibleProfile.OPENROUTER, transport)

        client.generate(request(), EphemeralCredential("secret"))

        val body = transport.lastRequest!!.body
        assertTrue(body.contains("\"model\":\"openrouter/free\""))
        assertTrue(body.contains("\"json_object\""))
        assertTrue(body.contains("\"data_collection\":\"deny\""))
        assertTrue(body.contains("\"prompt\":0"))
        assertTrue(body.contains("\"completion\":0"))
    }

    private fun request() = EditorialReportRequest("private", "hash", items = listOf(GeminiEditorialProviderClientTest.pageItem()))

    private fun envelope(text: String) =
        "{\"choices\":[{\"message\":{\"content\":${JSONObject.quote(text)}}}]}"
}

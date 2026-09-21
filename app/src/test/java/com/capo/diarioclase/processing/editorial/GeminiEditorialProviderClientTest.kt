package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.semantic.ClaimProvenance
import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.FakeInferenceHttpTransport
import com.capo.diarioclase.processing.semantic.ProviderOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiEditorialProviderClientTest {
    @Test
    fun `gemini sends native editorial schema and accepted evidence only`() = runTest {
        val transport = FakeInferenceHttpTransport.ok(200, geminiEnvelope(validReport))
        val client = GeminiEditorialProviderClient(transport)

        val outcome = client.generate(request(), EphemeralCredential("secret"))

        assertTrue(outcome is ProviderOutcome.Success)
        assertEquals(validReport, (outcome as ProviderOutcome.Success).rawJson)
        val recorded = transport.lastRequest!!
        assertTrue(recorded.body.contains("responseJsonSchema"))
        assertTrue(recorded.body.contains("accepted_evidence"))
        assertTrue(recorded.body.contains("claim-page-42"))
        assertFalse(recorded.body.contains("session-real-id"))
        assertEquals("secret", recorded.headers["x-goog-api-key"])
    }

    @Test
    fun `gemini repair includes sanitized validation findings`() = runTest {
        val transport = FakeInferenceHttpTransport.ok(200, geminiEnvelope(validReport))
        val client = GeminiEditorialProviderClient(transport)

        client.generate(
            request(),
            EphemeralCredential("secret"),
            EditorialRepair(setOf(EditorialIssue.MISSING_CLAIM), setOf("claim-page-42")),
        )

        val body = transport.lastRequest!!.body
        assertTrue(body.contains("MISSING_CLAIM"))
        assertTrue(body.contains("claim-page-42"))
        assertFalse(body.contains("session-real-id"))
    }

    private fun request() = EditorialReportRequest(
        sessionId = "session-real-id",
        inputHash = "hash",
        items = listOf(pageItem()),
    )

    companion object {
        const val validReport =
            "{\"summary\":\"\",\"material\":[{\"text\":\"Página 42.\",\"source_claim_ids\":[\"claim-page-42\"]}],\"homework\":[],\"summary_source_claim_ids\":[],\"discarded\":[]}"

        fun pageItem() = EditorialEvidenceItem(
            "claim-page-42", ClaimCategory.PAGE, ClaimStatus.PERFORMED, "Página 42", "42",
            "Abrimos la página 42", 1, 1, 1, ClaimOrigin.GEMINI, ClaimProvenance.REMOTE, emptyList(),
        )

        fun geminiEnvelope(text: String) =
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":${org.json.JSONObject.quote(text)}}]}}]}"
    }
}

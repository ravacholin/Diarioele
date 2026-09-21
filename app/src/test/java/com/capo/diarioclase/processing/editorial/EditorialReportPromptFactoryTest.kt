package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.semantic.ClaimProvenance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialReportPromptFactoryTest {
    private val factory = EditorialReportPromptFactory()

    @Test
    fun `prompt sends accepted evidence without local session id`() {
        val prompt = factory.create(request())

        assertTrue(prompt.userText.contains("<accepted_evidence>"))
        assertTrue(prompt.userText.contains("claim-page-42"))
        assertTrue(prompt.userText.contains("Página 42"))
        assertFalse(prompt.userText.contains("private-session-id"))
    }

    @Test
    fun `prompt requires direct authored sections and literal preservation`() {
        val system = factory.create(request()).systemInstruction.lowercase()

        assertTrue(system.contains("español rioplatense"))
        assertTrue(system.contains("no agregues"))
        assertTrue(system.contains("números"))
        assertTrue(system.contains("source_claim_ids"))
        assertTrue(system.contains("material"))
        assertTrue(system.contains("homework"))
    }

    @Test
    fun `repair exposes only issue codes and missing claim ids`() {
        val prompt = factory.create(
            request(),
            EditorialRepair(
                issues = setOf(EditorialIssue.MISSING_CLAIM, EditorialIssue.MISSING_LITERAL),
                missingClaimIds = setOf("claim-page-42"),
            ),
        )

        assertTrue(prompt.systemInstruction.contains("MISSING_CLAIM"))
        assertTrue(prompt.systemInstruction.contains("MISSING_LITERAL"))
        assertTrue(prompt.systemInstruction.contains("claim-page-42"))
        assertFalse(prompt.systemInstruction.contains("private-session-id"))
    }

    @Test
    fun `schema is closed at root and nested objects`() {
        val schema = factory.create(request()).jsonSchema

        assertTrue(schema.contains("\"additionalProperties\":false"))
        assertTrue(schema.contains("\"source_claim_ids\""))
        assertTrue(schema.contains("\"summary_source_claim_ids\""))
        assertTrue(schema.contains("\"discarded\""))
    }

    private fun request() = EditorialReportRequest(
        sessionId = "private-session-id",
        inputHash = "hash",
        items = listOf(
            EditorialEvidenceItem(
                claimId = "claim-page-42",
                category = ClaimCategory.PAGE,
                status = ClaimStatus.PERFORMED,
                value = "Página 42",
                normalizedValue = "42",
                excerpt = "Abrimos la página cuarenta y dos.",
                blockOrdinal = 1,
                segmentOrdinal = 2,
                spanOrdinal = 3,
                origin = ClaimOrigin.GEMINI,
                provenance = ClaimProvenance.REMOTE,
                supersedesClaimKeys = emptyList(),
            ),
        ),
    )
}

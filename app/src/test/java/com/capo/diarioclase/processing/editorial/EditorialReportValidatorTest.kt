package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.semantic.ClaimProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialReportValidatorTest {
    private val validator = EditorialReportValidator()

    @Test
    fun `validator rejects missing page claim coverage`() {
        val outcome = validator.validate(emptyReport, request(item("page-42", ClaimCategory.PAGE, "42")))

        assertEquals(setOf(EditorialIssue.MISSING_CLAIM), invalid(outcome).issues)
        assertEquals(setOf("page-42"), invalid(outcome).missingClaimIds)
    }

    @Test
    fun `assigned exercise used only in material is rejected`() {
        val raw = report(material = output("Ejercicio 5.", "exercise-5"))
        val outcome = validator.validate(
            raw,
            request(item("exercise-5", ClaimCategory.EXERCISE, "5", ClaimStatus.ASSIGNED)),
        )

        assertTrue(EditorialIssue.WRONG_DESTINATION in invalid(outcome).issues)
    }

    @Test
    fun `linked material must retain page literal`() {
        val outcome = validator.validate(
            report(material = output("Página 24.", "page-42")),
            request(item("page-42", ClaimCategory.PAGE, "42")),
        )

        assertTrue(EditorialIssue.MISSING_LITERAL in invalid(outcome).issues)
    }

    @Test
    fun `unknown and repeated references are rejected`() {
        val raw = report(material = output("Página 42.", "page-42", "page-42", "ghost"))
        val outcome = validator.validate(raw, request(item("page-42", ClaimCategory.PAGE, "42")))

        assertTrue(EditorialIssue.UNKNOWN_CLAIM in invalid(outcome).issues)
        assertTrue(EditorialIssue.DUPLICATE_REFERENCE in invalid(outcome).issues)
    }

    @Test
    fun `topic may be discarded with reason while pedagogical numbers stay visible`() {
        val raw = """
            {
              "summary":"",
              "material":[{"text":"Página 42, ejercicio 3.","source_claim_ids":["page-42","exercise-3"]}],
              "homework":[{"text":"Terminar el ejercicio 5.","source_claim_ids":["exercise-5"]}],
              "summary_source_claim_ids":[],
              "discarded":[{"claim_id":"topic-noise","reason":"Comentario incidental"}]
            }
        """.trimIndent()
        val outcome = validator.validate(
            raw,
            request(
                item("topic-noise", ClaimCategory.TOPIC, "charla"),
                item("page-42", ClaimCategory.PAGE, "42"),
                item("exercise-3", ClaimCategory.EXERCISE, "3"),
                item("exercise-5", ClaimCategory.EXERCISE, "5", ClaimStatus.ASSIGNED),
            ),
        )

        assertTrue(outcome is EditorialValidationOutcome.Valid)
    }

    @Test
    fun `page exercise and homework cannot be discarded`() {
        val categories = listOf(ClaimCategory.PAGE, ClaimCategory.EXERCISE, ClaimCategory.HOMEWORK)
        categories.forEach { category ->
            val id = category.name.lowercase()
            val outcome = validator.validate(
                report(discarded = "{\"claim_id\":\"$id\",\"reason\":\"ruido\"}"),
                request(item(id, category, "42")),
            )
            assertTrue("category=$category", EditorialIssue.PROTECTED_CLAIM_DISCARDED in invalid(outcome).issues)
        }
    }

    private fun invalid(outcome: EditorialValidationOutcome): EditorialValidationOutcome.Invalid {
        assertTrue("Expected Invalid, got $outcome", outcome is EditorialValidationOutcome.Invalid)
        return outcome as EditorialValidationOutcome.Invalid
    }

    private fun request(vararg items: EditorialEvidenceItem) = EditorialReportRequest(
        sessionId = "session",
        inputHash = "hash",
        items = items.toList(),
    )

    private fun item(
        id: String,
        category: ClaimCategory,
        normalized: String,
        status: ClaimStatus = ClaimStatus.PERFORMED,
    ) = EditorialEvidenceItem(
        claimId = id,
        category = category,
        status = status,
        value = normalized,
        normalizedValue = normalized,
        excerpt = "evidence $normalized",
        blockOrdinal = 1,
        segmentOrdinal = 1,
        spanOrdinal = 1,
        origin = ClaimOrigin.GEMINI,
        provenance = ClaimProvenance.REMOTE,
        supersedesClaimKeys = emptyList(),
    )

    private fun output(text: String, vararg ids: String) =
        "{\"text\":\"$text\",\"source_claim_ids\":[${ids.joinToString(",") { "\"$it\"" }}]}"

    private fun report(
        material: String = "",
        homework: String = "",
        discarded: String = "",
    ) = """
        {
          "summary":"",
          "material":[${material}],
          "homework":[${homework}],
          "summary_source_claim_ids":[],
          "discarded":[${discarded}]
        }
    """.trimIndent()

    private companion object {
        val emptyReport = """
            {"summary":"","material":[],"homework":[],"summary_source_claim_ids":[],"discarded":[]}
        """.trimIndent()
    }
}

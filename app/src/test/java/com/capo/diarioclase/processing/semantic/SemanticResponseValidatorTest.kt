package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.evidence.ClaimOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SemanticResponseValidatorTest {

    private val validator = SemanticResponseValidator()

    private val s1 = PublicTranscriptSpan("B1-S1", 1, 1, 1, 0, 1_000, "Página cuarenta y dos.", contextOnly = false)
    private val s2 = PublicTranscriptSpan("B1-S2", 1, 1, 2, 1_000, 2_000, "contexto anterior", contextOnly = true)
    private val spans = listOf(s1, s2)

    private fun claim(
        key: String = "B1-C1",
        category: String = "PAGE",
        value: String = "42",
        normalized: String = "42",
        status: String = "PERFORMED",
        confidence: Double = 0.95,
        evidence: List<String> = listOf("B1-S1"),
        supersedes: List<String> = emptyList(),
        evidenceQuote: String? = null,
    ) = ProviderSemanticClaim(key, category, value, normalized, status, confidence, evidence, supersedes, evidenceQuote)

    private fun json(vararg claims: ProviderSemanticClaim) = ProviderClaimsCodec.encode(claims.toList())

    private fun validate(rawJson: String) = validator.validate(rawJson, InferenceProvider.GEMINI, spans)

    @Test
    fun `valid response builds evidence from local spans and tags origin`() {
        val outcome = validate(json(claim()))
        assertTrue(outcome is ValidationOutcome.Valid)
        val claims = (outcome as ValidationOutcome.Valid).claims
        assertEquals(1, claims.size)
        val claim = claims.single()
        assertEquals(ClaimOrigin.GEMINI, claim.origin)
        assertEquals("B1-C1", claim.claimKey)
        // La evidencia proviene del span local, no de texto del modelo.
        assertEquals("Página cuarenta y dos.", claim.evidence.excerpt)
        assertEquals(1, claim.evidences.size)
        assertEquals(1, claim.evidence.blockOrdinal)
        assertEquals(1, claim.evidence.audioSegmentOrdinal)
        assertEquals(1, claim.evidence.spanOrdinal)
        assertEquals(false, claim.evidence.contextual)
    }

    @Test
    fun `keeps an evidence quote that is a real substring of a cited span`() {
        val outcome = validate(json(claim(evidenceQuote = "cuarenta y dos")))
        val claim = (outcome as ValidationOutcome.Valid).claims.single()
        assertEquals("cuarenta y dos", claim.evidenceQuote)
    }

    @Test
    fun `drops an evidence quote that no cited span backs`() {
        val outcome = validate(json(claim(evidenceQuote = "página noventa")))
        val claim = (outcome as ValidationOutcome.Valid).claims.single()
        assertEquals(null, claim.evidenceQuote)
    }

    @Test
    fun `an absent evidence quote decodes and validates as null`() {
        val outcome = validate(json(claim()))
        assertEquals(null, (outcome as ValidationOutcome.Valid).claims.single().evidenceQuote)
    }

    @Test
    fun `rejects unknown category and status`() {
        assertEquals(ValidationFailure.UNKNOWN_CATEGORY, invalidReason(validate(json(claim(category = "COLOR")))))
        assertEquals(ValidationFailure.UNKNOWN_STATUS, invalidReason(validate(json(claim(status = "MAYBE")))))
    }

    @Test
    fun `rejects confidence out of range`() {
        assertEquals(ValidationFailure.CONFIDENCE_OUT_OF_RANGE, invalidReason(validate(json(claim(confidence = 1.5)))))
        assertEquals(ValidationFailure.CONFIDENCE_OUT_OF_RANGE, invalidReason(validate(json(claim(confidence = -0.1)))))
    }

    @Test
    fun `rejects empty or oversized value`() {
        assertEquals(ValidationFailure.INVALID_VALUE, invalidReason(validate(json(claim(value = "")))))
        assertEquals(ValidationFailure.INVALID_VALUE, invalidReason(validate(json(claim(value = "a".repeat(301))))))
    }

    @Test
    fun `rejects more than one hundred claims`() {
        val many = (1..101).map { claim(key = "C$it") }.toTypedArray()
        assertEquals(ValidationFailure.TOO_MANY_CLAIMS, invalidReason(validate(json(*many))))
    }

    @Test
    fun `rejects evidence that does not exist`() {
        assertEquals(ValidationFailure.UNKNOWN_EVIDENCE, invalidReason(validate(json(claim(evidence = listOf("B9-S9"))))))
    }

    @Test
    fun `rejects evidence that is only context`() {
        assertEquals(ValidationFailure.CONTEXT_ONLY_EVIDENCE, invalidReason(validate(json(claim(evidence = listOf("B1-S2"))))))
    }

    @Test
    fun `rejects missing evidence`() {
        assertEquals(ValidationFailure.MISSING_EVIDENCE, invalidReason(validate(json(claim(evidence = emptyList())))))
    }

    @Test
    fun `rejects malformed json`() {
        assertEquals(ValidationFailure.MALFORMED_JSON, invalidReason(validate("{ not json")))
    }

    @Test
    fun `rejects self and unknown supersessions`() {
        assertEquals(
            ValidationFailure.SELF_SUPERSEDE,
            invalidReason(validate(json(claim(key = "B1-C1", supersedes = listOf("B1-C1"))))),
        )
        assertEquals(
            ValidationFailure.UNKNOWN_SUPERSEDE,
            invalidReason(validate(json(claim(key = "B1-C1", supersedes = listOf("ghost"))))),
        )
    }

    @Test
    fun `rejects supersession cycles`() {
        val a = claim(key = "A", value = "1", normalized = "1", supersedes = listOf("B"))
        val b = claim(key = "B", value = "2", normalized = "2", supersedes = listOf("A"))
        assertEquals(ValidationFailure.SUPERSEDE_CYCLE, invalidReason(validate(json(a, b))))
    }

    @Test
    fun `rejects duplicate claim keys`() {
        val a = claim(key = "DUP", value = "1", normalized = "1")
        val b = claim(key = "DUP", value = "2", normalized = "2")
        assertEquals(ValidationFailure.DUPLICATE_CLAIM_KEY, invalidReason(validate(json(a, b))))
    }

    private fun invalidReason(outcome: ValidationOutcome): ValidationFailure {
        assertTrue("esperaba Invalid, fue $outcome", outcome is ValidationOutcome.Invalid)
        return (outcome as ValidationOutcome.Invalid).reason
    }
}

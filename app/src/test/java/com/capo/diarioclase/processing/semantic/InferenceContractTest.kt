package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pruebas del contrato común de Fase 5 (Task 1). Congelan la forma del contrato: modelos,
 * códec JSON, invariantes de ids, evidencia múltiple, supersesiones, orden de spans, fake
 * configurable y política estado→campo. Las aserciones end-to-end del router y el
 * validador pertenecen a Tasks 5 y 7. Ninguna prueba usa red ni credenciales reales.
 *
 * Robolectric provee `org.json` real en las pruebas de JVM.
 */
@RunWith(RobolectricTestRunner::class)
class InferenceContractTest {

    // --- Códec: serialización y parsing ------------------------------------------------

    @Test
    fun `decode parses the canonical design fixture`() {
        val fixture = """
            {
              "claims": [
                {
                  "claim_key": "B2-C1",
                  "category": "EXERCISE",
                  "value": "3 (p. 42)",
                  "normalized_value": "3 (p. 42)",
                  "status": "PERFORMED",
                  "confidence": 0.96,
                  "evidence_span_ids": ["B2-S12", "B2-S13"],
                  "supersedes_claim_keys": []
                }
              ]
            }
        """.trimIndent()

        val claims = ProviderClaimsCodec.decode(fixture)

        assertEquals(1, claims.size)
        val claim = claims.single()
        assertEquals("B2-C1", claim.claimKey)
        assertEquals("EXERCISE", claim.category)
        assertEquals("3 (p. 42)", claim.value)
        assertEquals("3 (p. 42)", claim.normalizedValue)
        assertEquals("PERFORMED", claim.status)
        assertEquals(0.96, claim.confidence, 1e-9)
        assertEquals(listOf("B2-S12", "B2-S13"), claim.evidenceSpanIds)
        assertTrue(claim.supersedesClaimKeys.isEmpty())
    }

    @Test
    fun `encode then decode is a stable round trip for every scenario`() {
        SyntheticInterpretationScenarios.all().forEach { scenario ->
            val json = ProviderClaimsCodec.encode(scenario.gold)
            val decoded = ProviderClaimsCodec.decode(json)
            assertEquals("round-trip falló en ${scenario.name}", scenario.gold, decoded)
        }
    }

    @Test
    fun `decode reads an optional evidence quote and round-trips it`() {
        val fixture = """
            {"claims":[{"claim_key":"B1-C1","category":"PAGE","value":"12",
            "normalized_value":"12","status":"PERFORMED","confidence":0.9,
            "evidence_span_ids":["B1-S1"],"supersedes_claim_keys":[],
            "evidence_quote":"página dolce"}]}
        """.trimIndent()
        val claim = ProviderClaimsCodec.decode(fixture).single()
        assertEquals("página dolce", claim.evidenceQuote)
        // Round-trip: al re-serializar y decodificar, la cita se conserva.
        assertEquals(claim, ProviderClaimsCodec.decode(ProviderClaimsCodec.encode(listOf(claim))).single())
    }

    @Test
    fun `decode reads an optional reason and a root summary`() {
        val fixture = """
            {"summary":"Repasamos saludos y vimos la página 12.",
            "claims":[{"claim_key":"B1-C1","category":"TOPIC","value":"saludos",
            "normalized_value":"saludos","status":"PERFORMED","confidence":0.9,
            "evidence_span_ids":["B1-S1"],"supersedes_claim_keys":[],
            "evidence_quote":"","reason":"Se practicaron saludos al inicio"}]}
        """.trimIndent()
        val claim = ProviderClaimsCodec.decode(fixture).single()
        assertEquals("Se practicaron saludos al inicio", claim.reason)
        assertEquals("Repasamos saludos y vimos la página 12.", ProviderClaimsCodec.summaryOf(fixture))
    }

    @Test
    fun `summaryOf returns null when summary is blank or absent`() {
        assertEquals(null, ProviderClaimsCodec.summaryOf("""{"claims":[],"summary":""}"""))
        assertEquals(null, ProviderClaimsCodec.summaryOf("""{"claims":[]}"""))
        assertEquals(null, ProviderClaimsCodec.summaryOf("{ not json"))
    }

    @Test
    fun `decode treats a blank or absent evidence quote as null`() {
        val blank = """
            {"claims":[{"claim_key":"B1-C1","category":"TOPIC","value":"saludos",
            "normalized_value":"saludos","status":"PERFORMED","confidence":0.9,
            "evidence_span_ids":["B1-S1"],"supersedes_claim_keys":[],"evidence_quote":""}]}
        """.trimIndent()
        assertEquals(null, ProviderClaimsCodec.decode(blank).single().evidenceQuote)
    }

    @Test
    fun `decode rejects malformed json`() {
        assertThrows(ContractParseException::class.java) {
            ProviderClaimsCodec.decode("{ not json")
        }
    }

    @Test
    fun `decode rejects a missing claims property`() {
        assertThrows(ContractParseException::class.java) {
            ProviderClaimsCodec.decode("""{"items": []}""")
        }
    }

    @Test
    fun `decode rejects a claim missing a required field`() {
        val missingStatus = """
            {"claims":[{"claim_key":"B1-C1","category":"PAGE","value":"42",
            "normalized_value":"42","confidence":0.9,"evidence_span_ids":["B1-S1"],
            "supersedes_claim_keys":[]}]}
        """.trimIndent()
        assertThrows(ContractParseException::class.java) {
            ProviderClaimsCodec.decode(missingStatus)
        }
    }

    @Test
    fun `decode rejects a non array evidence field`() {
        val badEvidence = """
            {"claims":[{"claim_key":"B1-C1","category":"PAGE","value":"42",
            "normalized_value":"42","status":"PERFORMED","confidence":0.9,
            "evidence_span_ids":"B1-S1","supersedes_claim_keys":[]}]}
        """.trimIndent()
        assertThrows(ContractParseException::class.java) {
            ProviderClaimsCodec.decode(badEvidence)
        }
    }

    // --- Invariantes de ids ------------------------------------------------------------

    @Test
    fun `public span ids follow the B block S span shape`() {
        assertEquals("B2-S17", PublicSpanId.of(2, 17))
        assertTrue(PublicSpanId.isValid("B2-S17"))
        assertFalse(PublicSpanId.isValid("2-17"))
        assertFalse(PublicSpanId.isValid("B2S17"))
        assertFalse(PublicSpanId.isValid("session-abc"))
    }

    @Test
    fun `every scenario span carries a valid public id`() {
        SyntheticInterpretationScenarios.all().forEach { scenario ->
            scenario.spans.forEach { span ->
                assertTrue(
                    "${scenario.name}: id inválido ${span.publicId}",
                    PublicSpanId.isValid(span.publicId),
                )
                assertEquals(
                    "${scenario.name}: publicId no coincide con ordinales",
                    PublicSpanId.of(span.blockOrdinal, span.spanOrdinal),
                    span.publicId,
                )
            }
        }
    }

    @Test
    fun `every gold claim references existing spans and known supersessions`() {
        SyntheticInterpretationScenarios.all().forEach { scenario ->
            val spanIds = scenario.spans.map { it.publicId }.toSet()
            val claimKeys = scenario.gold.map { it.claimKey }.toSet()
            scenario.gold.forEach { claim ->
                assertTrue("${scenario.name}: claimKey vacío", claim.claimKey.isNotBlank())
                assertTrue(
                    "${scenario.name}: claim ${claim.claimKey} sin evidencia",
                    claim.evidenceSpanIds.isNotEmpty(),
                )
                claim.evidenceSpanIds.forEach { spanId ->
                    assertTrue(
                        "${scenario.name}: evidencia $spanId inexistente",
                        spanId in spanIds,
                    )
                }
                claim.supersedesClaimKeys.forEach { superseded ->
                    assertTrue(
                        "${scenario.name}: supersede $superseded inexistente",
                        superseded in claimKeys,
                    )
                    assertNotEquals(
                        "${scenario.name}: claim ${claim.claimKey} se supersede a sí mismo",
                        claim.claimKey,
                        superseded,
                    )
                }
            }
        }
    }

    // --- Evidencia múltiple y supersesiones --------------------------------------------

    @Test
    fun `contract preserves multiple evidence spans`() {
        val exercise = SyntheticInterpretationScenarios.mainWalkthrough.gold
            .single { it.claimKey == "B2-C3" }
        assertEquals(listOf("B2-S2", "B2-S3"), exercise.evidenceSpanIds)
    }

    @Test
    fun `contract preserves supersession chains`() {
        val corrected = SyntheticInterpretationScenarios.mainWalkthrough.gold
            .single { it.claimKey == "B2-C4b" }
        assertEquals("ASSIGNED", corrected.status)
        assertEquals(listOf("B2-C4a"), corrected.supersedesClaimKeys)
    }

    // --- Orden de spans ----------------------------------------------------------------

    @Test
    fun `span order sorts by block then segment then span`() {
        val a = span(block = 1, segment = 2, spanOrdinal = 9, startMs = 100)
        val b = span(block = 1, segment = 3, spanOrdinal = 1, startMs = 50)
        val c = span(block = 2, segment = 1, spanOrdinal = 1, startMs = 0)
        val shuffled = listOf(c, b, a)

        val ordered = shuffled.sortedWith(SpanOrder)

        assertEquals(listOf(a, b, c), ordered)
    }

    @Test
    fun `span start time alone never reorders segments`() {
        // El segmento 1 empieza más tarde (startMs 500) que el segmento 2 (startMs 100),
        // pero el orden de segmento manda: el segmento 1 va primero.
        val earlierSegment = span(block = 1, segment = 1, spanOrdinal = 1, startMs = 500)
        val laterSegment = span(block = 1, segment = 2, spanOrdinal = 1, startMs = 100)

        val ordered = listOf(laterSegment, earlierSegment).sortedWith(SpanOrder)

        assertEquals(listOf(earlierSegment, laterSegment), ordered)
    }

    // --- Fake configurable -------------------------------------------------------------

    @Test
    fun `fake replays queued outcomes in order and records attempts`() = runTest {
        val fake = FakeInferenceProviderClient(
            provider = InferenceProvider.GEMINI,
            outcomes = listOf(
                FakeInferenceProviderClient.quotaFailure(InferenceProvider.GEMINI),
                FakeInferenceProviderClient.serverUnavailable(InferenceProvider.GEMINI),
                FakeInferenceProviderClient.success(InferenceProvider.GEMINI, """{"claims":[]}"""),
            ),
        )
        val request = request()

        val first = fake.infer(request, EphemeralCredential("k"))
        val second = fake.infer(request, EphemeralCredential("k"))
        val third = fake.infer(request, EphemeralCredential("k"))

        assertTrue(first is ProviderOutcome.Failure && first.code == ProviderFailure.QUOTA)
        assertTrue(second is ProviderOutcome.Failure && second.code == ProviderFailure.SERVER_UNAVAILABLE)
        assertTrue(third is ProviderOutcome.Success)
        assertEquals(3, fake.attempts)
        assertEquals(3, fake.credentialsSeen)
        assertEquals(listOf(request, request, request), fake.requests)
    }

    @Test
    fun `fake can simulate authentication timeout and invalid json`() = runTest {
        val auth = FakeInferenceProviderClient(
            InferenceProvider.GROQ,
            FakeInferenceProviderClient.authenticationFailure(InferenceProvider.GROQ),
        ).infer(request(), EphemeralCredential("k"))
        assertTrue(auth is ProviderOutcome.Failure && auth.code == ProviderFailure.AUTHENTICATION)
        assertEquals(401, (auth as ProviderOutcome.Failure).httpStatus)

        val timeout = FakeInferenceProviderClient(
            InferenceProvider.GROQ,
            FakeInferenceProviderClient.timeout(InferenceProvider.GROQ),
        ).infer(request(), EphemeralCredential("k"))
        assertTrue(timeout is ProviderOutcome.Failure && timeout.code == ProviderFailure.TIMEOUT)

        val invalid = FakeInferenceProviderClient(
            InferenceProvider.OPENROUTER,
            FakeInferenceProviderClient.invalidJson(InferenceProvider.OPENROUTER),
        ).infer(request(), EphemeralCredential("k"))
        assertTrue(invalid is ProviderOutcome.Success)
        // El fake entrega la respuesta “exitosa”, pero el códec la rechaza estructuralmente.
        assertThrows(ContractParseException::class.java) {
            ProviderClaimsCodec.decode((invalid as ProviderOutcome.Success).rawJson)
        }
    }

    @Test
    fun `fake repeats the last outcome when the queue is exhausted`() = runTest {
        val fake = FakeInferenceProviderClient(
            InferenceProvider.GEMINI,
            FakeInferenceProviderClient.quotaFailure(InferenceProvider.GEMINI),
        )
        val first = fake.infer(request(), EphemeralCredential("k"))
        val second = fake.infer(request(), EphemeralCredential("k"))
        assertEquals(first, second)
    }

    // --- Política estado→campo ---------------------------------------------------------

    @Test
    fun `performed claims feed their category class field`() {
        assertEquals(
            FieldTarget.Field(DiaryField.TOPICS),
            StatusFieldPolicy.target(ClaimCategory.TOPIC, ClaimStatus.PERFORMED),
        )
        assertEquals(
            FieldTarget.Field(DiaryField.ACTIVITIES),
            StatusFieldPolicy.target(ClaimCategory.ACTIVITY, ClaimStatus.PERFORMED),
        )
        assertEquals(
            FieldTarget.Field(DiaryField.PAGES),
            StatusFieldPolicy.target(ClaimCategory.PAGE, ClaimStatus.PERFORMED),
        )
        assertEquals(
            FieldTarget.Field(DiaryField.EXERCISES),
            StatusFieldPolicy.target(ClaimCategory.EXERCISE, ClaimStatus.PERFORMED),
        )
    }

    @Test
    fun `assigned claims feed homework regardless of category`() {
        assertEquals(
            FieldTarget.Field(DiaryField.HOMEWORK),
            StatusFieldPolicy.target(ClaimCategory.EXERCISE, ClaimStatus.ASSIGNED),
        )
        assertEquals(
            FieldTarget.Field(DiaryField.HOMEWORK),
            StatusFieldPolicy.target(ClaimCategory.PAGE, ClaimStatus.ASSIGNED),
        )
    }

    @Test
    fun `uncertain always requires confirmation`() {
        ClaimCategory.values().forEach { category ->
            assertEquals(
                "categoría $category",
                FieldTarget.Confirmation,
                StatusFieldPolicy.target(category, ClaimStatus.UNCERTAIN),
            )
        }
    }

    @Test
    fun `proposed cancelled and corrected stay as inactive history`() {
        listOf(ClaimStatus.PROPOSED, ClaimStatus.CANCELLED, ClaimStatus.CORRECTED).forEach { status ->
            assertEquals(
                "estado $status",
                FieldTarget.InactiveHistory,
                StatusFieldPolicy.target(ClaimCategory.EXERCISE, status),
            )
        }
    }

    // --- Helpers -----------------------------------------------------------------------

    private fun span(block: Int, segment: Int, spanOrdinal: Int, startMs: Long): PublicTranscriptSpan =
        PublicTranscriptSpan(
            publicId = PublicSpanId.of(block, spanOrdinal),
            blockOrdinal = block,
            audioSegmentOrdinal = segment,
            spanOrdinal = spanOrdinal,
            startMs = startMs,
            endMs = startMs + 1_000,
            text = "span $block/$segment/$spanOrdinal",
            contextOnly = false,
        )

    private fun request(): InterpretationRequest = InterpretationRequest(
        packetId = "packet-1",
        promptVersion = "free-ele-v1",
        schemaVersion = "claims-v1",
        spans = SyntheticInterpretationScenarios.mainWalkthrough.spans,
    )
}

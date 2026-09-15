package com.capo.diarioclase.processing.semantic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationPromptFactoryTest {

    private val factory = InterpretationPromptFactory()

    private fun request() = InterpretationRequest(
        packetId = "p1",
        promptVersion = "free-ele-v1",
        schemaVersion = "claims-v1",
        spans = listOf(
            PublicTranscriptSpan("B1-S1", 1, 1, 1, 0, 1_000, "Vamos a la página cuarenta.", contextOnly = false),
            PublicTranscriptSpan("B1-S2", 1, 1, 2, 1_000, 2_000, "Repaso.", contextOnly = true),
        ),
    )

    @Test
    fun `system instruction lists categories and statuses`() {
        val system = factory.create(request()).systemInstruction
        listOf("TOPIC", "ACTIVITY", "PAGE", "EXERCISE", "HOMEWORK").forEach {
            assertTrue("falta categoría $it", system.contains(it))
        }
        listOf("PERFORMED", "ASSIGNED", "PROPOSED", "CANCELLED", "CORRECTED", "UNCERTAIN").forEach {
            assertTrue("falta estado $it", system.contains(it))
        }
    }

    @Test
    fun `system instruction demands evidence and forbids inventing`() {
        val system = factory.create(request()).systemInstruction
        assertTrue(system.contains("evidence_span_ids"))
        assertTrue(system.contains("No inventes"))
    }

    @Test
    fun `system instruction covers questions quotes plans and self corrections`() {
        val system = factory.create(request()).systemInstruction.lowercase()
        assertTrue(system.contains("pregunta"))
        assertTrue(system.contains("cita"))
        assertTrue(system.contains("plan"))
        assertTrue(system.contains("autocorrec"))
    }

    @Test
    fun `prompt never mentions the interpretation mode`() {
        val prompt = factory.create(request())
        val text = (prompt.systemInstruction + prompt.userText).lowercase()
        assertFalse(text.contains("conservative"))
        assertFalse(text.contains("balanced"))
        assertFalse(text.contains("exhaustive"))
        assertFalse(text.contains("modo"))
    }

    @Test
    fun `user text carries public ids and marks context spans`() {
        val userText = factory.create(request()).userText
        assertTrue(userText.contains("[B1-S1|0-1000]"))
        assertTrue(userText.contains("[B1-S2|1000-2000]"))
        assertTrue(userText.contains("(contexto)"))
    }

    @Test
    fun `json schema requires every field and forbids extras`() {
        val schema = factory.create(request()).jsonSchema
        listOf(
            "claim_key", "category", "value", "normalized_value",
            "status", "confidence", "evidence_span_ids", "supersedes_claim_keys",
        ).forEach { assertTrue("falta campo $it en el esquema", schema.contains(it)) }
        assertTrue(schema.contains("\"additionalProperties\": false"))
    }
}

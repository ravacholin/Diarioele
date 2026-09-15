package com.capo.diarioclase.processing.work

import com.capo.diarioclase.processing.semantic.InterpretationPacket
import com.capo.diarioclase.processing.semantic.InterpretationRequest
import com.capo.diarioclase.processing.semantic.PublicTranscriptSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InterpretationRunModelsTest {

    @Test
    fun `packet keeps an exact local id mapping for every public span`() {
        val request = request(listOf(span("B1-S1"), span("B1-S2", ordinal = 2)))
        val sourceIds = mapOf("B1-S1" to "span-local-1", "B1-S2" to "span-local-2")

        val packet = InterpretationPacket(request, sourceIds)

        assertEquals(sourceIds, packet.sourceSpanIds)
        assertEquals(request, packet.request)
    }

    @Test
    fun `packet rejects a missing local span mapping`() {
        val request = request(listOf(span("B1-S1"), span("B1-S2", ordinal = 2)))

        assertThrows(IllegalArgumentException::class.java) {
            InterpretationPacket(request, mapOf("B1-S1" to "span-local-1"))
        }
    }

    @Test
    fun `packet rejects an extra local span mapping`() {
        val request = request(listOf(span("B1-S1")))

        assertThrows(IllegalArgumentException::class.java) {
            InterpretationPacket(
                request,
                mapOf("B1-S1" to "span-local-1", "B1-S2" to "span-local-2"),
            )
        }
    }

    @Test
    fun `semantic states remain exhaustive and stable for persistence`() {
        assertEquals(
            listOf("PENDING", "RUNNING", "REMOTE_OK", "LOCAL_OK", "MIXED_OK", "FAILED", "CANCELLED"),
            InterpretationRunState.entries.map { it.name },
        )
        assertEquals(
            listOf("PENDING", "RUNNING", "REMOTE_OK", "LOCAL_OK", "FAILED", "CANCELLED"),
            InterpretationPacketState.entries.map { it.name },
        )
        assertEquals(
            listOf("DEADLINE", "CANCELLED", "TRANSPORT", "INVALID_RESPONSE", "INSUFFICIENT_RESPONSE", "INTERNAL"),
            InterpretationFailure.entries.map { it.name },
        )
    }

    private fun request(spans: List<PublicTranscriptSpan>) = InterpretationRequest(
        packetId = "packet",
        promptVersion = "prompt-v1",
        schemaVersion = "schema-v1",
        spans = spans,
    )

    private fun span(publicId: String, ordinal: Int = 1) = PublicTranscriptSpan(
        publicId = publicId,
        blockOrdinal = 1,
        audioSegmentOrdinal = 1,
        spanOrdinal = ordinal,
        startMs = 0,
        endMs = 1,
        text = "texto",
        contextOnly = false,
    )
}

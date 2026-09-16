package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationPacketBudgetPropertyTest {

    private val sizer = InterpretationRequestSizer()

    @Test
    fun `unicode quotes newlines and long spans stay within byte budget without text loss`() {
        val maxRequestBytes = 6_000
        val originalText = buildString {
            repeat(1_000) {
                append("¿Qué dijo ‘él’? \"página\" 42 👩🏽‍🏫\n")
            }
        }
        val source = span("source-long", originalText)

        val packets = InterpretationPacketBuilder(
            maxCharacters = Int.MAX_VALUE,
            maxRequestBytes = maxRequestBytes,
            overlapSpans = 1,
        ).build(listOf(source))

        assertTrue(packets.size > 1)
        packets.forEach { packet ->
            assertTrue(sizer.estimatedBytes(packet) <= maxRequestBytes)
            assertEquals(setOf("source-long"), packet.sourceSpanIds.values.toSet())
        }
        val rebuilt = packets
            .flatMap { it.request.spans }
            .filterNot { it.contextOnly }
            .joinToString(separator = "") { it.text }
        assertEquals(originalText, rebuilt)
    }

    @Test
    fun `mixed unicode packets are deterministic and bounded`() {
        val maxRequestBytes = 7_000
        val spans = listOf(
            span("s1", "á".repeat(2_000), startMs = 8_000, segment = "A"),
            span("s2", "\\n\"漢字🙂".repeat(800), startMs = 9_000, segment = "A"),
            span("s3", "reinicio".repeat(900), startMs = 0, segment = "B"),
        )
        val builder = InterpretationPacketBuilder(
            maxCharacters = Int.MAX_VALUE,
            maxRequestBytes = maxRequestBytes,
        )

        val first = builder.build(spans)
        val second = builder.build(spans)

        assertEquals(first, second)
        assertTrue(first.all { sizer.estimatedBytes(it) <= maxRequestBytes })
    }

    private fun span(
        id: String,
        text: String,
        startMs: Long = 0,
        segment: String = "segment",
    ) = TranscriptSpan(
        id = id,
        audioSegmentId = segment,
        blockId = BlockId("block"),
        startMs = startMs,
        endMs = startMs + 1_000,
        text = text,
        confidence = 0.9,
    )
}

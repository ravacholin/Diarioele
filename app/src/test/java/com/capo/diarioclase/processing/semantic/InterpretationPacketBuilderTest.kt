package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationPacketBuilderTest {

    @Test
    fun `empty input produces no packets`() {
        assertTrue(InterpretationPacketBuilder().build(emptyList()).isEmpty())
    }

    @Test
    fun `orders spans by block and time and assigns public ids`() {
        // Entrada desordenada dentro de un segmento y con dos bloques entremezclados.
        val spans = listOf(
            transcriptSpan(block = "blk-A", segment = "seg-1", start = 3_000, text = "tercero"),
            transcriptSpan(block = "blk-A", segment = "seg-1", start = 1_000, text = "primero"),
            transcriptSpan(block = "blk-B", segment = "seg-9", start = 500, text = "otro bloque"),
            transcriptSpan(block = "blk-A", segment = "seg-2", start = 2_000, text = "segundo"),
        )

        val packets = InterpretationPacketBuilder().build(spans)

        // Bloque A (primera aparición) es B1; bloque B es B2, en orden de aparición.
        val blockA = packets.first { it.request.spans.any { s -> s.blockOrdinal == 1 } }
        val ordered = blockA.request.spans.filterNot { it.contextOnly }
        // Se ordena seg-1 internamente y luego se conserva seg-2, aunque su reloj sea menor.
        assertEquals(listOf("primero", "tercero", "segundo"), ordered.map { it.text })
        assertEquals(listOf("B1-S1", "B1-S2", "B1-S3"), ordered.map { it.publicId })

        val blockB = packets.first { it.request.spans.any { s -> s.blockOrdinal == 2 } }
        assertEquals(listOf("B2-S1"), blockB.request.spans.filterNot { it.contextOnly }.map { it.publicId })
    }

    @Test
    fun `every public id follows the artificial shape and leaks no raw ids`() {
        val rawBlock = "session-block-uuid-1234"
        val rawSegment = "audio-segment-uuid-5678"
        val spans = listOf(
            transcriptSpan(block = rawBlock, segment = rawSegment, start = 0, text = "hola"),
            transcriptSpan(block = rawBlock, segment = rawSegment, start = 1_000, text = "chau"),
        )

        val packets = InterpretationPacketBuilder().build(spans)

        packets.flatMap { it.request.spans }.forEach { span ->
            assertTrue("id no artificial: ${span.publicId}", PublicSpanId.isValid(span.publicId))
            assertNotEquals(rawBlock, span.publicId)
            assertNotEquals(rawSegment, span.publicId)
        }
    }

    @Test
    fun `splits a block when it exceeds the character limit`() {
        val builder = InterpretationPacketBuilder(maxCharacters = 20, overlapSpans = 2, preferredPauseMs = 4_000)
        // Cinco spans de 5 caracteres, sin pausas grandes: 20 caracteres caben, el quinto corta.
        val spans = (1..5).map { i ->
            transcriptSpan(block = "b", segment = "s", start = i * 1_000L, end = i * 1_000L + 500, text = "xxxxx")
        }

        val packets = builder.build(spans)

        assertEquals(2, packets.size)
        assertEquals(4, packets[0].request.spans.count { !it.contextOnly })
        assertEquals(1, packets[1].request.spans.count { !it.contextOnly })
    }

    @Test
    fun `prefers to cut at a pause of at least the preferred threshold`() {
        val builder = InterpretationPacketBuilder(maxCharacters = 20, overlapSpans = 2, preferredPauseMs = 4_000)
        val spans = listOf(
            transcriptSpan(block = "b", segment = "s", start = 0, end = 1_000, text = "aaaaa"),
            // Pausa de 4000 ms antes de este span: es la frontera preferente.
            transcriptSpan(block = "b", segment = "s", start = 5_000, end = 6_000, text = "bbbbb"),
            transcriptSpan(block = "b", segment = "s", start = 6_100, end = 7_000, text = "ccccc"),
            transcriptSpan(block = "b", segment = "s", start = 7_100, end = 8_000, text = "ddddd"),
            transcriptSpan(block = "b", segment = "s", start = 8_100, end = 9_000, text = "eeeee"),
        )

        val packets = builder.build(spans)

        assertEquals(2, packets.size)
        // El corte cae en la pausa: el primer paquete termina en el span anterior a la pausa.
        val firstMain = packets[0].request.spans.filterNot { it.contextOnly }
        assertEquals(listOf("aaaaa"), firstMain.map { it.text })
        val secondMain = packets[1].request.spans.filterNot { it.contextOnly }
        assertEquals(listOf("bbbbb", "ccccc", "ddddd", "eeeee"), secondMain.map { it.text })
    }

    @Test
    fun `repeats the last two spans as context in the following packet`() {
        val builder = InterpretationPacketBuilder(maxCharacters = 20, overlapSpans = 2, preferredPauseMs = 4_000)
        val spans = (1..5).map { i ->
            transcriptSpan(block = "b", segment = "s", start = i * 1_000L, end = i * 1_000L + 500, text = "xxxxx")
        }

        val packets = builder.build(spans)

        // El primer paquete no lleva contexto.
        assertTrue(packets[0].request.spans.none { it.contextOnly })
        // El segundo repite los dos últimos spans del primero como contextOnly.
        val context = packets[1].request.spans.filter { it.contextOnly }
        assertEquals(2, context.size)
        assertEquals(listOf("B1-S3", "B1-S4"), context.map { it.publicId })
        assertTrue(context.all { it.contextOnly })
    }

    @Test
    fun `packet id is a deterministic sha256 hex of versions and text`() {
        val spans = listOf(
            transcriptSpan(block = "b", segment = "s", start = 0, end = 1_000, text = "hola"),
            transcriptSpan(block = "b", segment = "s", start = 1_000, end = 2_000, text = "mundo"),
        )

        val first = InterpretationPacketBuilder().build(spans)
        val second = InterpretationPacketBuilder().build(spans)

        assertEquals(first.map { it.request.packetId }, second.map { it.request.packetId })
        first.forEach { packet ->
            assertEquals(64, packet.request.packetId.length)
            assertTrue(packet.request.packetId.all { it in "0123456789abcdef" })
        }

        // Cambiar el texto cambia el packetId.
        val altered = InterpretationPacketBuilder().build(
            listOf(
                transcriptSpan(block = "b", segment = "s", start = 0, end = 1_000, text = "hola"),
                transcriptSpan(block = "b", segment = "s", start = 1_000, end = 2_000, text = "planeta"),
            ),
        )
        assertNotEquals(first.single().request.packetId, altered.single().request.packetId)
    }

    @Test
    fun `packet carries the frozen prompt and schema versions`() {
        val spans = listOf(transcriptSpan(block = "b", segment = "s", start = 0, end = 1_000, text = "hola"))
        val packet = InterpretationPacketBuilder().build(spans).single()
        assertEquals("free-ele-v1", packet.request.promptVersion)
        assertEquals("claims-v1", packet.request.schemaVersion)
    }

    @Test
    fun `segment clock reset does not interleave segments`() {
        val spans = listOf(
            transcriptSpan(block = "b", segment = "A", start = 8_000, text = "página veinte"),
            transcriptSpan(block = "b", segment = "A", start = 9_000, text = "ejercicio dos"),
            transcriptSpan(block = "b", segment = "B", start = 0, text = "no, el tres"),
        )

        val result = InterpretationPacketBuilder().build(spans)
            .flatMap { it.request.spans }
            .filterNot { it.contextOnly }
            .map { it.text }

        assertEquals(listOf("página veinte", "ejercicio dos", "no, el tres"), result)
    }

    @Test
    fun `every public id maps back to its local transcript span`() {
        val spans = listOf(
            transcriptSpan(block = "b", segment = "A", start = 0, text = "uno"),
            transcriptSpan(block = "b", segment = "A", start = 1_000, text = "dos"),
        )

        val packets = InterpretationPacketBuilder().build(spans)

        packets.forEach { packet ->
            packet.request.spans.forEach { publicSpan ->
                val sourceId = packet.sourceSpanIds.getValue(publicSpan.publicId)
                assertTrue(sourceId in spans.map { it.id })
            }
        }
    }

    private fun transcriptSpan(
        block: String,
        segment: String,
        start: Long,
        end: Long = start + 1_000,
        text: String,
        confidence: Double = 0.9,
    ): TranscriptSpan = TranscriptSpan(
        id = "span-$block-$segment-$start",
        audioSegmentId = segment,
        blockId = BlockId(block),
        startMs = start,
        endMs = end,
        text = text,
        confidence = confidence,
    )
}

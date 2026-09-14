package com.capo.diarioclase.processing.transcription

import com.capo.diarioclase.data.db.BlockId
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptDeduplicatorTest {
    @Test
    fun `overlap keeps only spans after confirmed boundary`() {
        val prior = listOf(span("a", 0, 30_000, "página doce"))
        val next = listOf(
            span("b", 28_000, 30_000, "página doce"),
            span("c", 30_000, 35_000, "ejercicio tres"),
        )

        assertEquals(
            listOf("página doce", "ejercicio tres"),
            TranscriptDeduplicator.merge(prior, next).map { it.text },
        )
    }

    @Test
    fun `normalized duplicate crossing the overlap boundary is discarded`() {
        val prior = listOf(span("a", 27_000, 30_000, "Página doce."))
        val next = listOf(
            span("b", 29_000, 31_000, "pagina doce"),
            span("c", 31_000, 33_000, "actividad oral"),
        )

        assertEquals(
            listOf("Página doce.", "actividad oral"),
            TranscriptDeduplicator.merge(prior, next).map { it.text },
        )
    }

    @Test
    fun `same words later in the class are not discarded`() {
        val prior = listOf(span("a", 0, 1_000, "repasamos el presente"))
        val next = listOf(span("b", 5_000, 6_000, "repasamos el presente"))

        assertEquals(2, TranscriptDeduplicator.merge(prior, next).size)
    }

    private fun span(
        id: String,
        startMs: Long,
        endMs: Long,
        text: String,
    ) = TranscriptSpan(
        id = id,
        audioSegmentId = "segmento",
        blockId = BlockId("bloque"),
        startMs = startMs,
        endMs = endMs,
        text = text,
        confidence = 1.0,
    )
}

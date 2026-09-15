package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.data.db.BlockId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationProjectorTest {
    private fun claim(
        id: String,
        confidence: Double,
        status: ClaimStatus = ClaimStatus.PERFORMED,
    ) = EvidenceClaim(
        id, ClaimCategory.TOPIC, id, id, status, confidence,
        ClaimOrigin.LOCAL_RULE, EvidenceRef(BlockId("b"), 0, 1, "evidence"),
    )

    @Test
    fun `conservative mode separates certainty from confirmation`() {
        val result = InterpretationProjector().project(
            listOf(claim("certain", .9), claim("maybe", .7), claim("weak", .3)),
            InterpretationMode.CONSERVATIVE,
        )
        assertEquals(listOf("certain"), result.accepted.map { it.id })
        assertEquals(listOf("maybe"), result.confirm.map { it.id })
        assertEquals(listOf("weak"), result.hidden.map { it.id })
    }

    @Test
    fun `exhaustive mode exposes weaker evidence without inventing`() {
        val blank = claim("blank", .9).copy(evidence = EvidenceRef(BlockId("b"), 0, 1, ""))
        val result = InterpretationProjector().project(
            listOf(claim("weak", .56), blank),
            InterpretationMode.EXHAUSTIVE,
        )
        assertEquals(listOf("weak"), result.accepted.map { it.id })
        assertEquals(1, result.hidden.size)
    }

    @Test
    fun `uncertain always needs confirmation regardless of confidence`() {
        val result = InterpretationProjector().project(
            listOf(claim("dudoso", .99, ClaimStatus.UNCERTAIN)),
            InterpretationMode.CONSERVATIVE,
        )
        assertEquals(listOf("dudoso"), result.confirm.map { it.id })
        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun `proposed cancelled and corrected stay hidden`() {
        listOf(ClaimStatus.PROPOSED, ClaimStatus.CANCELLED, ClaimStatus.CORRECTED).forEach { status ->
            val result = InterpretationProjector().project(
                listOf(claim("x", .99, status)),
                InterpretationMode.EXHAUSTIVE,
            )
            assertEquals("estado $status", listOf("x"), result.hidden.map { it.id })
            assertTrue(result.accepted.isEmpty())
            assertTrue(result.confirm.isEmpty())
        }
    }
}

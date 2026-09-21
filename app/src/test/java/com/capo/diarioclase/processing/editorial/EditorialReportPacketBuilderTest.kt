package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.InterpretationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EditorialReportPacketBuilderTest {
    private val builder = EditorialReportPacketBuilder()

    @Test
    fun `packet contains only accepted active claims in evidence order`() {
        val confirm = claim("confirm", ClaimCategory.TOPIC, confidence = .70, block = 0, segment = 0, span = 0)
        val acceptedExercise = claim("exercise", ClaimCategory.EXERCISE, block = 2, segment = 1, span = 4)
        val acceptedPage = claim("page", ClaimCategory.PAGE, block = 1, segment = 3, span = 2)
        val inactive = claim("inactive", ClaimCategory.PAGE, block = 0, segment = 0, span = 0).copy(active = false)

        val request = builder.build(
            "session",
            InterpretationMode.CONSERVATIVE,
            listOf(confirm, acceptedExercise, inactive, acceptedPage),
        )

        assertEquals(listOf(acceptedPage.id, acceptedExercise.id), request.items.map { it.claimId })
    }

    @Test
    fun `same text with different claim ids remains twice`() {
        val first = claim("first", ClaimCategory.PAGE, value = "Página 42", block = 1, span = 1)
        val second = claim("second", ClaimCategory.PAGE, value = "Página 42", block = 1, span = 2)

        val request = builder.build("session", InterpretationMode.CONSERVATIVE, listOf(first, second))

        assertEquals(listOf(first.id, second.id), request.items.map { it.claimId })
    }

    @Test
    fun `input hash changes after claim correction`() {
        val original = claim("exercise", ClaimCategory.EXERCISE, value = "Ejercicio 3")

        val before = builder.build("session", InterpretationMode.CONSERVATIVE, listOf(original))
        val after = builder.build(
            "session",
            InterpretationMode.CONSERVATIVE,
            listOf(original.copy(value = "Ejercicio 4", normalizedValue = "4")),
        )

        assertNotEquals(before.inputHash, after.inputHash)
    }

    @Test
    fun `input hash is stable across incoming list order`() {
        val first = claim("first", ClaimCategory.PAGE, block = 1, span = 1)
        val second = claim("second", ClaimCategory.EXERCISE, block = 1, span = 2)

        val forward = builder.build("one", InterpretationMode.CONSERVATIVE, listOf(first, second))
        val reversed = builder.build("two", InterpretationMode.CONSERVATIVE, listOf(second, first))

        assertEquals(forward.inputHash, reversed.inputHash)
    }

    private fun claim(
        id: String,
        category: ClaimCategory,
        value: String = id,
        confidence: Double = .95,
        block: Int? = 0,
        segment: Int? = 0,
        span: Int? = 0,
    ): EvidenceClaim {
        val evidence = EvidenceRef(
            blockId = BlockId("block-$id"),
            startMs = 0,
            endMs = 100,
            excerpt = "evidence $id",
            blockOrdinal = block,
            audioSegmentOrdinal = segment,
            spanOrdinal = span,
        )
        return EvidenceClaim(
            id = id,
            category = category,
            value = value,
            normalizedValue = value.removePrefix("Página ").removePrefix("Ejercicio "),
            status = ClaimStatus.PERFORMED,
            confidence = confidence,
            origin = ClaimOrigin.SEMANTIC,
            evidence = evidence,
            evidences = listOf(evidence),
        )
    }
}

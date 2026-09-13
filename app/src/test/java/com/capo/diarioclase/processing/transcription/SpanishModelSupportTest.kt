package com.capo.diarioclase.processing.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class SpanishModelSupportTest {
    @Test fun `installed Spanish is selected instead of an unavailable Argentine variant`() {
        val choice = chooseSpanishModel(
            installed = listOf("en-US", "es-ES"),
            pending = emptyList(),
            supported = listOf("es-AR"),
        )

        assertEquals(SpanishModelChoice("es-ES", SpanishModelAvailability.READY), choice)
    }

    @Test fun `Argentine Spanish wins when several installed variants exist`() {
        val choice = chooseSpanishModel(
            installed = listOf("es-MX", "es-AR", "es-ES"),
            pending = emptyList(),
            supported = emptyList(),
        )

        assertEquals(SpanishModelChoice("es-AR", SpanishModelAvailability.READY), choice)
    }

    @Test fun `pending Spanish is reported instead of requesting it forever`() {
        val choice = chooseSpanishModel(
            installed = emptyList(),
            pending = listOf("es-US"),
            supported = listOf("es-ES"),
        )

        assertEquals(SpanishModelChoice("es-US", SpanishModelAvailability.PENDING), choice)
    }

    @Test fun `supported Spanish is selected for download`() {
        val choice = chooseSpanishModel(
            installed = emptyList(),
            pending = emptyList(),
            supported = listOf("fr-FR", "es-MX"),
        )

        assertEquals(SpanishModelChoice("es-MX", SpanishModelAvailability.DOWNLOADABLE), choice)
    }

    @Test fun `absence of every Spanish variant is explicit`() {
        val choice = chooseSpanishModel(
            installed = listOf("en-US"),
            pending = listOf("fr-FR"),
            supported = listOf("de-DE"),
        )

        assertEquals(SpanishModelChoice(null, SpanishModelAvailability.UNSUPPORTED), choice)
    }
}

package com.capo.diarioclase.processing.editorial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EditorialReportCodecTest {
    @Test
    fun `codec rejects unknown root properties`() {
        assertThrows(EditorialContractException::class.java) {
            EditorialReportCodec.decode(validJson.replaceFirst("{", "{\"extra\":true,"))
        }
    }

    @Test
    fun `codec rejects unknown item properties`() {
        assertThrows(EditorialContractException::class.java) {
            EditorialReportCodec.decode(validJson.replace("\"text\":", "\"extra\":true,\"text\":"))
        }
    }

    @Test
    fun `codec round trip preserves authored text and audit ids`() {
        val decoded = EditorialReportCodec.decode(validJson)

        assertEquals("Resumen exacto.", decoded.summary)
        assertEquals("Página 42, ejercicio 3.", decoded.material.single().text)
        assertEquals(listOf("page-42", "exercise-3"), decoded.material.single().sourceClaimIds)
        assertEquals(decoded, EditorialReportCodec.decode(EditorialReportCodec.encode(decoded)))
    }

    private companion object {
        val validJson = """
            {
              "summary":"Resumen exacto.",
              "material":[{"text":"Página 42, ejercicio 3.","source_claim_ids":["page-42","exercise-3"]}],
              "homework":[],
              "summary_source_claim_ids":["topic"],
              "discarded":[]
            }
        """.trimIndent()
    }
}

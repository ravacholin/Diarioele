package com.capo.diarioclase.processing.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimIdentityTest {

    @Test
    fun `same provider key in two packets never collides`() {
        val a = ClaimIdentity.id("run", "packet-a", InferenceProvider.GEMINI, "C1")
        val b = ClaimIdentity.id("run", "packet-b", InferenceProvider.GEMINI, "C1")

        assertNotEquals(a, b)
    }

    @Test
    fun `identity is stable`() {
        assertEquals(
            ClaimIdentity.id("run", "packet", InferenceProvider.GROQ, "C1"),
            ClaimIdentity.id("run", "packet", InferenceProvider.GROQ, "C1"),
        )
    }

    @Test
    fun `length prefixes keep ambiguous component boundaries distinct`() {
        val a = ClaimIdentity.id("a|1:b", "c", InferenceProvider.OPENROUTER, "d")
        val b = ClaimIdentity.id("a", "b|1:c", InferenceProvider.OPENROUTER, "d")

        assertNotEquals(a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }
}

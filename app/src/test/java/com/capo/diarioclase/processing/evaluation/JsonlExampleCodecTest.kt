package com.capo.diarioclase.processing.evaluation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas del códec JSONL del corpus (Task Q6): round-trip estable y un ejemplo por línea.
 */
class JsonlExampleCodecTest {

    private val codec = JsonlExampleCodec()

    private fun example(seed: String) = LocalEvaluationExample(
        formatVersion = 1,
        pipelineVersions = PipelineVersions("0.6.0", "w1", "p1", "s1", "v1", "quality-score-v1"),
        spans = listOf(
            ExampleSpan("S0", "Vamos a la página cuarenta $seed", contextOnly = false),
            ExampleSpan("S1", "Contexto", contextOnly = true),
        ),
        automaticClaims = listOf(
            ExampleClaim("PAGE", "40", "PERFORMED", "BOTH", 0.93, listOf("S0")),
        ),
        revisions = listOf(
            ExampleRevision("PAGES", "40", "41", "CORRECT"),
        ),
        finalFields = mapOf("PAGES" to "41", "TOPICS" to "contraste"),
    )

    @Test
    fun `encode then decode is a stable round trip`() {
        val original = example("a")
        assertEquals(original, codec.decode(codec.encode(original)))
    }

    @Test
    fun `encode all writes one example per line and decodes back`() {
        val examples = listOf(example("a"), example("b"))
        val jsonl = codec.encodeAll(examples)
        assertEquals(2, jsonl.split("\n").size)
        assertEquals(examples, codec.decodeAll(jsonl))
    }
}

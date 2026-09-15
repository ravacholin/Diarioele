package com.capo.diarioclase.processing.semantic

/**
 * Escenarios sintéticos mínimos para la Fase 5. No hay audio real ni corpus: cada
 * escenario es una transcripción representada como spans públicos más la respuesta
 * “gold” que un proveedor correcto debería producir.
 *
 * Task 1 los usa para congelar el contrato (round-trip, invariantes de ids, evidencia
 * múltiple, supersesiones, orden y política estado→campo). Las aserciones end-to-end
 * del router (Task 7) y del validador (Task 5) reutilizarán estos mismos escenarios.
 *
 * Cada error que aparezca luego en el teléfono se agrega acá como el escenario mínimo
 * que lo reproduzca.
 */
object SyntheticInterpretationScenarios {

    /**
     * Un escenario. [gold] es una respuesta válida ejemplar; [prohibited] documenta lo
     * que un proveedor **no** debe afirmar (se verifica end-to-end en Tasks 5 y 7).
     */
    data class Scenario(
        val name: String,
        val spans: List<PublicTranscriptSpan>,
        val gold: List<ProviderSemanticClaim>,
        val prohibited: List<String> = emptyList(),
        val notes: String = "",
    )

    fun all(): List<Scenario> = listOf(
        mainWalkthrough,
        listsAndRanges,
        numberWordsAndPageChange,
        homeworkWithoutWord,
        quoteIsNotHomework,
        ambiguousReferent,
        evidenceSpreadAcrossSpans,
        duplicationAcrossPackets,
    )

    // --- Recorrido principal del plan ---------------------------------------------------

    val mainWalkthrough: Scenario by lazy {
        val spans = listOf(
            span(2, 1, "Hoy trabajamos el contraste entre perfecto e indefinido.", 0, 5_000),
            span(2, 2, "Vamos a la página cuarenta y dos.", 5_000, 9_000),
            span(2, 3, "Hacemos los ejercicios tres y cuatro.", 9_000, 13_000),
            span(2, 4, "El cuatro no, perdón, queda para casa.", 13_000, 17_000),
            span(2, 5, "La próxima clase vamos a ver los pronombres.", 17_000, 21_000),
            span(2, 6, "¿Hicieron el ejercicio cinco?", 21_000, 24_000),
        )
        Scenario(
            name = "main-walkthrough",
            spans = spans,
            gold = listOf(
                claim("B2-C1", "TOPIC", "contraste entre perfecto e indefinido", "PERFORMED", 0.96, listOf("B2-S1")),
                claim("B2-C2", "PAGE", "42", "PERFORMED", 0.98, listOf("B2-S2")),
                claim("B2-C3", "EXERCISE", "3 (p. 42)", "PERFORMED", 0.95, listOf("B2-S2", "B2-S3")),
                // El ejercicio 4 se enuncia como hecho y luego se corrige a tarea: supersesión.
                claim("B2-C4a", "EXERCISE", "4 (p. 42)", "PERFORMED", 0.55, listOf("B2-S3")),
                claim(
                    "B2-C4b", "EXERCISE", "4 (p. 42)", "ASSIGNED", 0.94,
                    evidenceSpanIds = listOf("B2-S3", "B2-S4"),
                    supersedes = listOf("B2-C4a"),
                ),
                // Plan futuro: propuesto, no realizado.
                claim("B2-C5", "TOPIC", "pronombres", "PROPOSED", 0.9, listOf("B2-S5")),
            ),
            prohibited = listOf(
                "pronombres como TOPIC PERFORMED (es un plan futuro)",
                "ejercicio 5 como EXERCISE PERFORMED o ASSIGNED (es una pregunta del alumnado)",
            ),
            notes = "Página 42; ejercicio 3 hecho; ejercicio 4 como tarea; pronombres solo propuestos.",
        )
    }

    // --- Listas y rangos ---------------------------------------------------------------

    val listsAndRanges: Scenario by lazy {
        val spans = listOf(
            span(1, 1, "En la página quince hacemos los ejercicios del uno al tres.", 0, 6_000),
            span(1, 2, "Y también el cinco.", 6_000, 9_000),
        )
        Scenario(
            name = "lists-and-ranges",
            spans = spans,
            gold = listOf(
                claim("B1-C1", "PAGE", "15", "PERFORMED", 0.97, listOf("B1-S1")),
                claim("B1-C2", "EXERCISE", "1 (p. 15)", "PERFORMED", 0.9, listOf("B1-S1")),
                claim("B1-C3", "EXERCISE", "2 (p. 15)", "PERFORMED", 0.9, listOf("B1-S1")),
                claim("B1-C4", "EXERCISE", "3 (p. 15)", "PERFORMED", 0.9, listOf("B1-S1")),
                claim("B1-C5", "EXERCISE", "5 (p. 15)", "PERFORMED", 0.88, listOf("B1-S2")),
            ),
            notes = "Rango 1 al 3 se expande a claims individuales; el 5 se agrega aparte.",
        )
    }

    // --- Números en palabras y cambio de página ----------------------------------------

    val numberWordsAndPageChange: Scenario by lazy {
        val spans = listOf(
            span(3, 1, "Abrimos en la página treinta y ocho.", 0, 4_000),
            span(3, 2, "Hacemos el ejercicio dos.", 4_000, 7_000),
            span(3, 3, "Ahora pasamos a la página cuarenta.", 7_000, 11_000),
            span(3, 4, "Y hacemos el ejercicio uno.", 11_000, 14_000),
        )
        Scenario(
            name = "number-words-and-page-change",
            spans = spans,
            gold = listOf(
                claim("B3-C1", "PAGE", "38", "PERFORMED", 0.97, listOf("B3-S1")),
                claim("B3-C2", "EXERCISE", "2 (p. 38)", "PERFORMED", 0.94, listOf("B3-S1", "B3-S2")),
                claim("B3-C3", "PAGE", "40", "PERFORMED", 0.97, listOf("B3-S3")),
                claim("B3-C4", "EXERCISE", "1 (p. 40)", "PERFORMED", 0.94, listOf("B3-S3", "B3-S4")),
            ),
            notes = "El ejercicio uno pertenece a la página 40, no a la 38: el contexto de página cambia.",
        )
    }

    // --- Tarea sin la palabra “tarea” --------------------------------------------------

    val homeworkWithoutWord: Scenario by lazy {
        val spans = listOf(
            span(4, 1, "El ejercicio siete lo terminan en casa.", 0, 4_000),
        )
        Scenario(
            name = "homework-without-word",
            spans = spans,
            gold = listOf(
                claim("B4-C1", "EXERCISE", "7", "ASSIGNED", 0.92, listOf("B4-S1")),
            ),
            notes = "‘Lo terminan en casa’ es tarea aunque no aparezca la palabra ‘tarea’.",
        )
    }

    // --- Cita / ejemplo que no es tarea ------------------------------------------------

    val quoteIsNotHomework: Scenario by lazy {
        val spans = listOf(
            span(5, 1, "El enunciado dice: hagan el ejercicio nueve en su casa.", 0, 5_000),
            span(5, 2, "Pero eso es solo un ejemplo del libro.", 5_000, 9_000),
        )
        Scenario(
            name = "quote-is-not-homework",
            spans = spans,
            gold = emptyList(),
            prohibited = listOf(
                "ejercicio 9 como ASSIGNED (es una cita/ejemplo del libro, no una consigna real)",
            ),
            notes = "Una cita o ejemplo no debe convertirse en tarea ni actividad.",
        )
    }

    // --- Referente ambiguo → UNCERTAIN -------------------------------------------------

    val ambiguousReferent: Scenario by lazy {
        val spans = listOf(
            span(6, 1, "Hicimos el ejercicio tres y el cuatro.", 0, 4_000),
            span(6, 2, "Ese lo corregimos la próxima.", 4_000, 8_000),
        )
        Scenario(
            name = "ambiguous-referent",
            spans = spans,
            gold = listOf(
                claim("B6-C1", "EXERCISE", "3", "PERFORMED", 0.9, listOf("B6-S1")),
                claim("B6-C2", "EXERCISE", "4", "PERFORMED", 0.9, listOf("B6-S1")),
                // “Ese” es ambiguo entre el 3 y el 4.
                claim("B6-C3", "EXERCISE", "corrección pendiente", "UNCERTAIN", 0.4, listOf("B6-S2")),
            ),
            notes = "‘Ese’ tiene dos antecedentes posibles: queda UNCERTAIN para confirmación.",
        )
    }

    // --- Evidencia distribuida entre varios spans --------------------------------------

    val evidenceSpreadAcrossSpans: Scenario by lazy {
        val spans = listOf(
            span(7, 1, "Estamos en la página treinta.", 0, 3_000),
            span(7, 2, "Y bueno, hicimos, a ver, el ejercicio dos.", 3_000, 8_000),
        )
        Scenario(
            name = "evidence-spread-across-spans",
            spans = spans,
            gold = listOf(
                claim("B7-C1", "PAGE", "30", "PERFORMED", 0.96, listOf("B7-S1")),
                claim("B7-C2", "EXERCISE", "2 (p. 30)", "PERFORMED", 0.93, listOf("B7-S1", "B7-S2")),
            ),
            notes = "El ejercicio 2 depende de la página del span anterior: evidencia en dos spans.",
        )
    }

    // --- Duplicación entre paquetes (spans de contexto) --------------------------------

    val duplicationAcrossPackets: Scenario by lazy {
        val spans = listOf(
            // Los dos últimos spans del paquete anterior se repiten como contexto.
            span(8, 1, "Hicimos el ejercicio cinco.", 0, 3_000, contextOnly = true),
            span(8, 2, "Corregimos entre todos.", 3_000, 6_000, contextOnly = true),
            span(8, 3, "Después seguimos con el ejercicio seis.", 6_000, 10_000),
        )
        Scenario(
            name = "duplication-across-packets",
            spans = spans,
            gold = listOf(
                claim("B8-C1", "EXERCISE", "6", "PERFORMED", 0.92, listOf("B8-S3")),
            ),
            prohibited = listOf(
                "ejercicio 5 nuevamente (ya afirmado en el paquete anterior; acá es solo contexto)",
            ),
            notes = "Los spans contextOnly no deben originar claims nuevos; evitan duplicar entre paquetes.",
        )
    }

    // --- Helpers -----------------------------------------------------------------------

    private fun span(
        block: Int,
        spanOrdinal: Int,
        text: String,
        startMs: Long,
        endMs: Long,
        contextOnly: Boolean = false,
        audioSegmentOrdinal: Int = spanOrdinal,
    ): PublicTranscriptSpan = PublicTranscriptSpan(
        publicId = PublicSpanId.of(block, spanOrdinal),
        blockOrdinal = block,
        audioSegmentOrdinal = audioSegmentOrdinal,
        spanOrdinal = spanOrdinal,
        startMs = startMs,
        endMs = endMs,
        text = text,
        contextOnly = contextOnly,
    )

    private fun claim(
        claimKey: String,
        category: String,
        value: String,
        status: String,
        confidence: Double,
        evidenceSpanIds: List<String>,
        normalizedValue: String = value,
        supersedes: List<String> = emptyList(),
    ): ProviderSemanticClaim = ProviderSemanticClaim(
        claimKey = claimKey,
        category = category,
        value = value,
        normalizedValue = normalizedValue,
        status = status,
        confidence = confidence,
        evidenceSpanIds = evidenceSpanIds,
        supersedesClaimKeys = supersedes,
    )
}

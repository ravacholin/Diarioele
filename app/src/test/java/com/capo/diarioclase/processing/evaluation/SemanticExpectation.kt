package com.capo.diarioclase.processing.evaluation

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.semantic.DiaryField

/**
 * Expectativa tipada de un claim (Task I6). Reemplaza las prohibiciones en prosa de los
 * escenarios por una identidad canónica y comparable: categoría + valor normalizado + estado.
 */
data class ClaimExpectation(
    val category: ClaimCategory,
    val normalizedValue: String,
    val status: ClaimStatus,
)

/**
 * Contrato de integridad de un escenario. `required` son los claims activos que el pipeline
 * debe producir; `prohibited` los que jamás debe dejar activos; `expectedFields` fija el
 * contenido exacto de cada campo de la ficha que interesa afirmar.
 */
data class SemanticExpectation(
    val required: Set<ClaimExpectation> = emptySet(),
    val prohibited: Set<ClaimExpectation> = emptySet(),
    val expectedFields: Map<DiaryField, String> = emptyMap(),
)

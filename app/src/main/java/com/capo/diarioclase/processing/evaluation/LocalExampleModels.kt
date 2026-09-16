package com.capo.diarioclase.processing.evaluation

import com.capo.diarioclase.data.db.DraftFieldRevision
import com.capo.diarioclase.processing.evidence.EvidenceClaim

/**
 * Modelos del corpus local de regresión (Fase 6, Q6).
 *
 * Un [LocalEvaluationExample] es un ejemplo inspeccionable y anonimizado: nunca contiene audio,
 * id de sesión, fechas absolutas ni rutas de archivo. Los spans llevan un id artificial por
 * orden (`S0`, `S1`, …), no el id real de transcripción. Sirve para evaluar y mejorar prompts
 * sin subir audio ni exigir un corpus inicial. Es opt-in, borrable y excluido del backup.
 */

/** Versiones del pipeline que produjeron el ejemplo (sin datos personales). */
data class PipelineVersions(
    val appVersion: String,
    val whisperVersion: String,
    val promptVersion: String,
    val schemaVersion: String,
    val validatorVersion: String,
    val scoreVersion: String,
)

/** Span anonimizado: id artificial por orden, texto y si es solo contexto (vecino conservado). */
data class ExampleSpan(
    val publicId: String,
    val text: String,
    val contextOnly: Boolean,
)

/** Claim automático anonimizado, con su evidencia referida a los [ExampleSpan] conservados. */
data class ExampleClaim(
    val category: String,
    val normalizedValue: String,
    val status: String,
    val provenance: String,
    val effectiveConfidence: Double,
    val evidencePublicIds: List<String>,
)

/** Una revisión del docente (aceptar/rechazar/corregir), sin id de sesión ni de claim. */
data class ExampleRevision(
    val field: String,
    val beforeValue: String,
    val afterValue: String,
    val action: String,
)

/** Ejemplo completo y versionado del corpus. */
data class LocalEvaluationExample(
    val formatVersion: Int = 1,
    val pipelineVersions: PipelineVersions,
    val spans: List<ExampleSpan>,
    val automaticClaims: List<ExampleClaim>,
    val revisions: List<ExampleRevision>,
    val finalFields: Map<String, String>,
)

/** Span de origen mínimo (id real de transcripción y texto), previo a la anonimización. */
data class ExampleSourceSpan(val id: String, val text: String)

/**
 * Fuente de datos del corpus. La implementación de producción (Q7) la resuelve desde Room; en
 * las pruebas se usa una fuente en memoria. Entrega spans ordenados, claims automáticos,
 * revisiones y campos finales de una sesión, más las versiones del pipeline.
 */
interface LocalExampleSource {
    suspend fun orderedSpans(sessionId: String): List<ExampleSourceSpan>
    suspend fun claims(sessionId: String): List<EvidenceClaim>
    suspend fun revisions(sessionId: String): List<DraftFieldRevision>
    suspend fun finalFields(sessionId: String): Map<String, String>
    fun pipelineVersions(): PipelineVersions
}

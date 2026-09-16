package com.capo.diarioclase.processing.evaluation

/**
 * Construye un [LocalEvaluationExample] anonimizado a partir de una sesión (Fase 6, Q6).
 *
 * Conserva solo los spans citados por algún claim y, como contexto, un vecino a cada lado; los
 * renumera con ids artificiales secuenciales. No incluye audio, id de sesión, fechas ni rutas.
 * El [LocalExampleRedactor] aplica una segunda pasada sobre el texto libre.
 */
class LocalExampleRepository(
    private val source: LocalExampleSource,
    private val redactor: LocalExampleRedactor = LocalExampleRedactor(),
) {

    suspend fun buildExample(sessionId: String): LocalEvaluationExample {
        val spans = source.orderedSpans(sessionId)
        val claims = source.claims(sessionId)
        val revisions = source.revisions(sessionId)
        val fields = source.finalFields(sessionId)

        val citedIds = claims.flatMap { it.transcriptSpanIds }.toSet()
        val citedIndices = spans.indices.filter { spans[it].id in citedIds }.toSet()
        val keep = sortedSetOf<Int>()
        citedIndices.forEach { i -> intArrayOf(i - 1, i, i + 1).forEach { if (it in spans.indices) keep += it } }

        val anonById = HashMap<String, String>()
        val exampleSpans = keep.mapIndexed { position, index ->
            val artificialId = "S$position"
            anonById[spans[index].id] = artificialId
            ExampleSpan(artificialId, spans[index].text, contextOnly = index !in citedIndices)
        }

        val exampleClaims = claims.map { claim ->
            ExampleClaim(
                category = claim.category.name,
                normalizedValue = claim.normalizedValue,
                status = claim.status.name,
                provenance = claim.provenance.name,
                effectiveConfidence = claim.effectiveConfidence,
                evidencePublicIds = claim.transcriptSpanIds.mapNotNull { anonById[it] },
            )
        }

        val exampleRevisions = revisions.map {
            ExampleRevision(it.field, it.beforeValue, it.afterValue, it.action.name)
        }

        val example = LocalEvaluationExample(
            formatVersion = 1,
            pipelineVersions = source.pipelineVersions(),
            spans = exampleSpans,
            automaticClaims = exampleClaims,
            revisions = exampleRevisions,
            finalFields = fields,
        )
        return redactor.redact(example, sessionId)
    }
}

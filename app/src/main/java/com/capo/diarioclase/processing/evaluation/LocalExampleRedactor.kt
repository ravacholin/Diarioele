package com.capo.diarioclase.processing.evaluation

/**
 * Saneador del corpus local (Fase 6, Q6). Elimina de todo texto libre cualquier residuo
 * sensible: id de sesión, rutas de audio (`.wav`) y fechas absolutas (ISO). El ejemplo ya se
 * construye sin esos campos; el redactor es la segunda barrera sobre el texto de spans, campos
 * finales y revisiones.
 */
class LocalExampleRedactor {

    fun redact(example: LocalEvaluationExample, sessionId: String): LocalEvaluationExample =
        example.copy(
            spans = example.spans.map { it.copy(text = scrub(it.text, sessionId)) },
            automaticClaims = example.automaticClaims.map { it.copy(normalizedValue = scrub(it.normalizedValue, sessionId)) },
            revisions = example.revisions.map {
                it.copy(beforeValue = scrub(it.beforeValue, sessionId), afterValue = scrub(it.afterValue, sessionId))
            },
            finalFields = example.finalFields.mapValues { scrub(it.value, sessionId) },
        )

    fun scrub(text: String, sessionId: String): String {
        var out = text
        if (sessionId.isNotBlank()) out = out.replace(sessionId, "[id]")
        out = ISO_DATE.replace(out, "[fecha]")
        out = WAV_PATH.replace(out, "[audio]")
        return out
    }

    companion object {
        private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val WAV_PATH = Regex("[\\w./-]+\\.wav")
    }
}

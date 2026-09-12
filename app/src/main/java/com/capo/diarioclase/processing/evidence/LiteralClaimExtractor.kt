package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.processing.transcription.TranscriptSpan
import java.util.UUID

class LiteralClaimExtractor(private val idProvider: () -> String = { UUID.randomUUID().toString() }) {
    fun extract(spans: List<TranscriptSpan>): List<RawClaim> {
        val claims = mutableListOf<RawClaim>()
        var activePage: Int? = null
        spans.forEach { span ->
            val text = SpanishNumberNormalizer.normalize(span.text).replace(Regex("[.,;:!?]"), " ").replace(Regex("\\s+"), " ").trim()
            val evidence = EvidenceRef(span.blockId, span.startMs, span.endMs, span.text.trim())
            val pageTail = Regex("\\b(?:vamos a la (?:pagina )?|pagina|pag)\\s+(.+)").find(text)?.groupValues?.get(1)
            SpanishNumberNormalizer.readPrefix(pageTail.orEmpty())?.first?.let { page ->
                activePage = page
                claims += claim(ClaimCategory.PAGE, page.toString(), ClaimStatus.PERFORMED, .98, evidence)
            }
            val exerciseTail = Regex("\\b(?:ejercicio|ejercicios|actividad|actividades)\\s+(.+)").find(text)?.groupValues?.get(1)
            SpanishNumberNormalizer.readPrefix(exerciseTail.orEmpty())?.first?.let { exercise ->
                val value = activePage?.let { "$exercise (p. $it)" } ?: exercise.toString()
                claims += claim(ClaimCategory.EXERCISE, value, ClaimStatus.PERFORMED, .94, evidence)
            }
            if (Regex("\\b(?:para (?:la )?(?:proxima clase|casa|manana)|de tarea|queda(?:n)? para)\\b").containsMatchIn(text)) {
                val value = span.text.trim()
                claims += claim(ClaimCategory.HOMEWORK, value, ClaimStatus.ASSIGNED, .90, evidence)
            }
            if (Regex("\\b(?:trabajamos|hicimos|practicamos|leimos|escuchamos|conversamos|escribimos|corregimos)\\b").containsMatchIn(text)) {
                claims += claim(ClaimCategory.ACTIVITY, span.text.trim(), ClaimStatus.PERFORMED, .86, evidence)
            }
            Regex("\\b(?:tema|vemos|vimos|trabajamos)\\s+(?:es|son|sobre|los|las|el|la)?\\s*(.+)").find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }?.let {
                claims += claim(ClaimCategory.TOPIC, it, ClaimStatus.PERFORMED, .78, evidence)
            }
        }
        return claims
    }

    private fun claim(category: ClaimCategory, value: String, status: ClaimStatus, confidence: Double, evidence: EvidenceRef) =
        RawClaim(idProvider(), category, value, SpanishNumberNormalizer.normalize(value), status, confidence, ClaimOrigin.LOCAL_RULE, evidence)
}

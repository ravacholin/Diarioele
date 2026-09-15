package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.evidence.LiteralClaimExtractor
import com.capo.diarioclase.processing.evidence.RawClaim
import com.capo.diarioclase.processing.transcription.TranscriptSpan

/**
 * Fallback local del router (Task 7): encapsula las reglas deterministas existentes
 * ([LiteralClaimExtractor]) para producir una ficha básica cuando ningún proveedor remoto
 * responde. Los claims resultantes usan `LOCAL_RULE`.
 *
 * Trabaja sobre los spans públicos del paquete (ignora los de contexto) y reconstruye un
 * `TranscriptSpan` con un `BlockId` sintético derivado del ordinal de bloque, igual que el
 * validador. No usa red.
 */
class FallbackClaimExtractor(
    private val literal: LiteralClaimExtractor = LiteralClaimExtractor(),
) {

    fun extract(spans: List<PublicTranscriptSpan>): List<RawClaim> {
        val local = spans.filterNot { it.contextOnly }.map { it.toTranscriptSpan() }
        return literal.extract(local)
    }

    private fun PublicTranscriptSpan.toTranscriptSpan(): TranscriptSpan = TranscriptSpan(
        id = publicId,
        audioSegmentId = "",
        blockId = BlockId("B$blockOrdinal"),
        startMs = startMs,
        endMs = endMs,
        text = text,
        confidence = 1.0,
    )
}

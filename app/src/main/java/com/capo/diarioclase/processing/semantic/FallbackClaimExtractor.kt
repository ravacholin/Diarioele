package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceRef
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
 *
 * Fase 6 (Q3): además de las reglas literales, incorpora [LocalInterpretationSignals] —los
 * marcadores manuales de tarea— como candidatos de tarea locales (`MANUAL_MARKER`). Los ids
 * de marcador y de bloque son locales y nunca viajan a un proveedor. El anclaje fino de cada
 * marcador a su span se afina en Q7; acá se ancla al span más cercano por tiempo.
 */
class FallbackClaimExtractor(
    private val literal: LiteralClaimExtractor = LiteralClaimExtractor(),
) {

    fun extract(
        spans: List<PublicTranscriptSpan>,
        signals: LocalInterpretationSignals = LocalInterpretationSignals(),
    ): List<RawClaim> {
        val content = spans.filterNot { it.contextOnly }
        val literalClaims = literal.extract(content.map { it.toTranscriptSpan() })
        return literalClaims + markerClaims(content, signals)
    }

    private fun markerClaims(
        content: List<PublicTranscriptSpan>,
        signals: LocalInterpretationSignals,
    ): List<RawClaim> {
        if (signals.markers.isEmpty() || content.isEmpty()) return emptyList()
        return signals.markers.mapNotNull { marker ->
            val span = spanForMarker(content, marker) ?: return@mapNotNull null
            RawClaim(
                id = "marker-${marker.markerId}",
                category = ClaimCategory.HOMEWORK,
                value = span.text.trim(),
                normalizedValue = span.text.trim(),
                status = ClaimStatus.ASSIGNED,
                confidence = 0.6,
                origin = ClaimOrigin.MANUAL_MARKER,
                evidence = span.toEvidence(),
                claimKey = "marker-${marker.markerId}",
                evidences = listOf(span.toEvidence()),
            )
        }
    }

    /** El span cuyo intervalo contiene el marcador; si ninguno, el último de contenido. */
    private fun spanForMarker(
        content: List<PublicTranscriptSpan>,
        marker: ManualMarkerSignal,
    ): PublicTranscriptSpan? =
        content.firstOrNull { marker.offsetMs in it.startMs..it.endMs } ?: content.lastOrNull()

    private fun PublicTranscriptSpan.toEvidence(): EvidenceRef = EvidenceRef(
        blockId = BlockId("B$blockOrdinal"),
        startMs = startMs,
        endMs = endMs,
        excerpt = text,
        blockOrdinal = blockOrdinal,
        audioSegmentOrdinal = audioSegmentOrdinal,
        spanOrdinal = spanOrdinal,
        contextual = contextOnly,
    )

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

package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.transcription.TranscriptSpan
import java.security.MessageDigest

/**
 * Construye paquetes textuales contextuales a partir de spans de Whisper (Task 3).
 *
 * La unidad primaria es el bloque de clase. Cada bloque se procesa por separado y se
 * corta en paquetes de a lo sumo [maxCharacters], prefiriendo cortar en pausas de al
 * menos [preferredPauseMs]. Los últimos [overlapSpans] spans de un paquete se repiten
 * como contexto (`contextOnly = true`) al inicio del siguiente paquete del mismo bloque,
 * para no perder referencias elípticas entre paquetes.
 *
 * El constructor es determinista y **no** hace detección semántica previa: no elige
 * fragmentos candidatos ni resume. Solo agrupa, ordena, asigna ids públicos artificiales
 * y calcula un `packetId` estable.
 *
 * Privacidad: la salida nunca contiene ids de sesión, de segmento de audio ni de bloque
 * reales, rutas ni nombres de archivo. Cada span queda identificado únicamente por un id
 * público `B<bloque>-S<span>` derivado del orden de entrada (ver [PublicSpanId]).
 */
class InterpretationPacketBuilder(
    private val maxCharacters: Int = 12_000,
    private val preferredPauseMs: Long = 4_000,
    private val overlapSpans: Int = 2,
    private val promptVersion: String = "free-ele-v1",
    private val schemaVersion: String = "claims-v1",
) {

    fun build(spans: List<TranscriptSpan>): List<InterpretationRequest> {
        if (spans.isEmpty()) return emptyList()

        val requests = mutableListOf<InterpretationRequest>()
        val orderedBlocks = groupIntoOrderedBlocks(spans)

        orderedBlocks.forEachIndexed { blockIndex, blockSpans ->
            val blockOrdinal = blockIndex + 1
            val publicSpans = assignPublicIds(blockSpans, blockOrdinal)
            val chunks = splitBlock(publicSpans)
            var previous: List<PublicTranscriptSpan> = emptyList()
            chunks.forEach { chunk ->
                val context = previous.takeLast(overlapSpans).map { it.copy(contextOnly = true) }
                val packetSpans = context + chunk
                requests += InterpretationRequest(
                    packetId = packetId(packetSpans),
                    promptVersion = promptVersion,
                    schemaVersion = schemaVersion,
                    spans = packetSpans,
                )
                previous = chunk
            }
        }
        return requests
    }

    /**
     * Agrupa por bloque en orden de primera aparición y ordena cada bloque por tiempo
     * de inicio, con desempate estable por el orden original de entrada.
     */
    private fun groupIntoOrderedBlocks(spans: List<TranscriptSpan>): List<List<TranscriptSpan>> {
        val order = LinkedHashMap<String, MutableList<IndexedValue<TranscriptSpan>>>()
        spans.forEachIndexed { index, span ->
            order.getOrPut(span.blockId.value) { mutableListOf() }.add(IndexedValue(index, span))
        }
        return order.values.map { blockSpans ->
            blockSpans.sortedWith(compareBy({ it.value.startMs }, { it.index })).map { it.value }
        }
    }

    /**
     * Asigna id público, ordinal de span y ordinal de segmento (por primera aparición del
     * segmento de audio dentro del bloque, ya ordenado por tiempo).
     */
    private fun assignPublicIds(
        blockSpans: List<TranscriptSpan>,
        blockOrdinal: Int,
    ): List<PublicTranscriptSpan> {
        val segmentOrdinals = LinkedHashMap<String, Int>()
        return blockSpans.mapIndexed { spanIndex, span ->
            val spanOrdinal = spanIndex + 1
            val segmentOrdinal = segmentOrdinals.getOrPut(span.audioSegmentId) { segmentOrdinals.size + 1 }
            PublicTranscriptSpan(
                publicId = PublicSpanId.of(blockOrdinal, spanOrdinal),
                blockOrdinal = blockOrdinal,
                audioSegmentOrdinal = segmentOrdinal,
                spanOrdinal = spanOrdinal,
                startMs = span.startMs,
                endMs = span.endMs,
                text = span.text,
                contextOnly = false,
            )
        }
    }

    /**
     * Divide un bloque en trozos que no superan [maxCharacters], prefiriendo cerrar en la
     * última pausa de al menos [preferredPauseMs] disponible antes de exceder el límite.
     * Un único span que ya supera el límite queda solo en su propio trozo.
     */
    private fun splitBlock(spans: List<PublicTranscriptSpan>): List<List<PublicTranscriptSpan>> {
        val chunks = mutableListOf<List<PublicTranscriptSpan>>()
        var current = mutableListOf<PublicTranscriptSpan>()
        var currentChars = 0
        var lastPauseBoundary = -1 // índice dentro de `current` donde conviene cortar

        fun flushUpTo(boundary: Int) {
            if (boundary <= 0 || boundary >= current.size) {
                chunks += current.toList()
                current = mutableListOf()
            } else {
                chunks += current.subList(0, boundary).toList()
                val rest = current.subList(boundary, current.size).toMutableList()
                current = rest
            }
            currentChars = current.sumOf { it.text.length }
            lastPauseBoundary = -1
        }

        spans.forEachIndexed { index, span ->
            val addedChars = span.text.length
            if (current.isNotEmpty() && currentChars + addedChars > maxCharacters) {
                // Preferir cerrar en la última pausa; si no hubo, cerrar completo.
                flushUpTo(if (lastPauseBoundary > 0) lastPauseBoundary else current.size)
            }
            // Registrar una pausa antes de este span como frontera preferente.
            if (current.isNotEmpty()) {
                val gap = span.startMs - current.last().endMs
                if (gap >= preferredPauseMs) lastPauseBoundary = current.size
            }
            current.add(span)
            currentChars += addedChars
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return chunks
    }

    /** SHA-256 hex de las versiones y de la representación textual exacta del paquete. */
    private fun packetId(spans: List<PublicTranscriptSpan>): String {
        val canonical = buildString {
            append(promptVersion).append('\n')
            append(schemaVersion).append('\n')
            spans.forEach { span ->
                append(span.publicId).append('|')
                append(span.startMs).append('-').append(span.endMs).append('|')
                append(if (span.contextOnly) "ctx" else "main").append('|')
                append(span.text).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

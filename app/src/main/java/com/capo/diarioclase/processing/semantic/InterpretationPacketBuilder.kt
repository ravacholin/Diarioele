package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.transcription.TranscriptSpan
import java.security.MessageDigest

/**
 * Construye paquetes textuales contextuales a partir de spans de Whisper (Task 3).
 *
 * La unidad primaria es el bloque de clase. Cada bloque se procesa por separado y se
 * corta en paquetes de a lo sumo [maxCharacters] y [maxRequestBytes], prefiriendo pausas
 * de al menos [preferredPauseMs]. Los últimos [overlapSpans] spans de un paquete se repiten
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
    private val maxRequestBytes: Int = 48_000,
    private val preferredPauseMs: Long = 4_000,
    private val overlapSpans: Int = 2,
    private val promptVersion: String = "free-ele-v1",
    private val schemaVersion: String = "claims-v1",
    private val requestSizer: InterpretationRequestSizer = InterpretationRequestSizer(),
) {

    private data class BoundSpan(
        val public: PublicTranscriptSpan,
        val sourceId: String,
    )

    fun build(spans: List<TranscriptSpan>): List<InterpretationPacket> {
        if (spans.isEmpty()) return emptyList()

        val packets = mutableListOf<InterpretationPacket>()
        val orderedBlocks = groupIntoOrderedBlocks(spans)

        orderedBlocks.forEachIndexed { blockIndex, blockSpans ->
            val blockOrdinal = blockIndex + 1
            val byteSafeSpans = blockSpans.flatMap { splitOversizedSpan(it, blockOrdinal) }
            val publicSpans = assignPublicIds(byteSafeSpans, blockOrdinal)
            val chunks = splitBlock(publicSpans).flatMap { splitByRequestBytes(it) }
            var previous: List<BoundSpan> = emptyList()
            chunks.forEach { chunk ->
                var context = previous.takeLast(overlapSpans)
                while (context.isNotEmpty() && !fitsRequest(context, chunk)) {
                    context = context.drop(1)
                }
                require(fitsRequest(context, chunk)) {
                    "A packet exceeds maxRequestBytes=$maxRequestBytes."
                }
                val boundPacketSpans = context.map { it.copy(public = it.public.copy(contextOnly = true)) } + chunk
                val requestSpans = boundPacketSpans.map { it.public }
                val request = InterpretationRequest(
                    packetId = packetId(requestSpans),
                    promptVersion = promptVersion,
                    schemaVersion = schemaVersion,
                    spans = requestSpans,
                )
                packets += InterpretationPacket(
                    request = request,
                    sourceSpanIds = boundPacketSpans.associate { it.public.publicId to it.sourceId },
                )
                previous = chunk
            }
        }
        return packets
    }

    /**
     * Agrupa por bloque y segmento en orden de primera aparición. Solo ordena por tiempo
     * dentro de un mismo segmento, porque el reloj se reinicia en cada audio.
     */
    private fun groupIntoOrderedBlocks(spans: List<TranscriptSpan>): List<List<TranscriptSpan>> {
        val order = LinkedHashMap<String, MutableList<IndexedValue<TranscriptSpan>>>()
        spans.forEachIndexed { index, span ->
            order.getOrPut(span.blockId.value) { mutableListOf() }.add(IndexedValue(index, span))
        }
        return order.values.map(::orderBlock)
    }

    private fun orderBlock(spans: List<IndexedValue<TranscriptSpan>>): List<TranscriptSpan> =
        spans.groupByTo(LinkedHashMap()) { it.value.audioSegmentId }
            .values
            .flatMap { segment ->
                segment.sortedWith(compareBy({ it.value.startMs }, { it.index })).map { it.value }
            }

    /**
     * Asigna id público, ordinal de span y ordinal de segmento (por primera aparición del
     * segmento de audio dentro del bloque, ya ordenado por tiempo).
     */
    private fun assignPublicIds(
        blockSpans: List<TranscriptSpan>,
        blockOrdinal: Int,
    ): List<BoundSpan> {
        val segmentOrdinals = LinkedHashMap<String, Int>()
        return blockSpans.mapIndexed { spanIndex, span ->
            val spanOrdinal = spanIndex + 1
            val segmentOrdinal = segmentOrdinals.getOrPut(span.audioSegmentId) { segmentOrdinals.size + 1 }
            BoundSpan(
                public = PublicTranscriptSpan(
                    publicId = PublicSpanId.of(blockOrdinal, spanOrdinal),
                    blockOrdinal = blockOrdinal,
                    audioSegmentOrdinal = segmentOrdinal,
                    spanOrdinal = spanOrdinal,
                    startMs = span.startMs,
                    endMs = span.endMs,
                    text = span.text,
                    contextOnly = false,
                ),
                sourceId = span.id,
            )
        }
    }

    /**
     * Divide un bloque en trozos que no superan [maxCharacters], prefiriendo cerrar en la
     * última pausa de al menos [preferredPauseMs] disponible antes de exceder el límite.
     * Un único span que ya supera el límite queda solo en su propio trozo.
     */
    private fun splitBlock(spans: List<BoundSpan>): List<List<BoundSpan>> {
        val chunks = mutableListOf<List<BoundSpan>>()
        var current = mutableListOf<BoundSpan>()
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
            currentChars = current.sumOf { it.public.text.length }
            lastPauseBoundary = -1
        }

        spans.forEach { span ->
            val addedChars = span.public.text.length
            if (current.isNotEmpty() && currentChars + addedChars > maxCharacters) {
                // Preferir cerrar en la última pausa; si no hubo, cerrar completo.
                flushUpTo(if (lastPauseBoundary > 0) lastPauseBoundary else current.size)
            }
            // Registrar una pausa antes de este span como frontera preferente.
            if (current.isNotEmpty()) {
                val gap = span.public.startMs - current.last().public.endMs
                if (gap >= preferredPauseMs) lastPauseBoundary = current.size
            }
            current.add(span)
            currentChars += addedChars
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return chunks
    }

    private fun splitByRequestBytes(spans: List<BoundSpan>): List<List<BoundSpan>> {
        val chunks = mutableListOf<List<BoundSpan>>()
        var current = mutableListOf<BoundSpan>()
        spans.forEach { span ->
            if (current.isNotEmpty() && !fitsRequest(emptyList(), current + span)) {
                chunks += current.toList()
                current = mutableListOf()
            }
            require(fitsRequest(emptyList(), listOf(span))) {
                "A single span exceeds maxRequestBytes=$maxRequestBytes after splitting."
            }
            current += span
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return chunks
    }

    private fun fitsRequest(context: List<BoundSpan>, main: List<BoundSpan>): Boolean {
        val requestSpans = context.map { it.public.copy(contextOnly = true) } + main.map { it.public }
        val request = InterpretationRequest(
            packetId = "0".repeat(64),
            promptVersion = promptVersion,
            schemaVersion = schemaVersion,
            spans = requestSpans,
        )
        return requestSizer.estimatedBytes(request) <= maxRequestBytes
    }

    private fun splitOversizedSpan(span: TranscriptSpan, blockOrdinal: Int): List<TranscriptSpan> {
        if (fitsSingleText(span.text, blockOrdinal)) return listOf(span)
        val pieces = mutableListOf<TranscriptSpan>()
        var start = 0
        while (start < span.text.length) {
            val end = largestFittingEnd(span.text, start, blockOrdinal)
            require(end > start) {
                "maxRequestBytes=$maxRequestBytes is too small for one Unicode code point."
            }
            pieces += span.copy(text = span.text.substring(start, end))
            start = end
        }
        return pieces
    }

    private fun largestFittingEnd(text: String, start: Int, blockOrdinal: Int): Int {
        val codePoints = text.codePointCount(start, text.length)
        var low = 1
        var high = codePoints
        var best = start
        while (low <= high) {
            val middle = (low + high) ushr 1
            val end = text.offsetByCodePoints(start, middle)
            if (fitsSingleText(text.substring(start, end), blockOrdinal)) {
                best = end
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun fitsSingleText(text: String, blockOrdinal: Int): Boolean {
        val publicSpan = PublicTranscriptSpan(
            publicId = PublicSpanId.of(blockOrdinal, 999_999),
            blockOrdinal = blockOrdinal,
            audioSegmentOrdinal = 999_999,
            spanOrdinal = 999_999,
            startMs = Long.MAX_VALUE,
            endMs = Long.MAX_VALUE,
            text = text,
            contextOnly = false,
        )
        val request = InterpretationRequest(
            packetId = "0".repeat(64),
            promptVersion = promptVersion,
            schemaVersion = schemaVersion,
            spans = listOf(publicSpan),
        )
        return requestSizer.estimatedBytes(request) <= maxRequestBytes
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

/** Measures the exact UTF-8 payload produced by the prompt renderer shared by clients. */
class InterpretationRequestSizer(
    private val promptFactory: InterpretationPromptFactory = InterpretationPromptFactory(),
) {
    fun estimatedBytes(packet: InterpretationPacket): Int = estimatedBytes(packet.request)

    fun estimatedBytes(request: InterpretationRequest): Int {
        val prompt = promptFactory.create(request)
        return buildString {
            append(prompt.systemInstruction).append('\n')
            append(prompt.userText).append('\n')
            append(prompt.jsonSchema)
        }.toByteArray(Charsets.UTF_8).size
    }
}

package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.evidence.RawClaim
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import com.capo.diarioclase.processing.work.InterpretationBudget
import com.capo.diarioclase.processing.work.InterpretationFailure
import com.capo.diarioclase.processing.work.InterpretationJournal
import com.capo.diarioclase.processing.work.InterpretationOutcome
import com.capo.diarioclase.processing.work.InterpretationPacketState
import com.capo.diarioclase.processing.work.InterpretationRunRecord
import com.capo.diarioclase.processing.work.InterpretationRunState
import com.capo.diarioclase.processing.work.InterpretationRunVersions
import com.capo.diarioclase.processing.work.ProviderAttemptRecord
import com.capo.diarioclase.processing.work.SemanticInterpreter
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Intérprete de producción (Task 8, acotado por Task I5): arma paquetes contextuales, los
 * rutea por la cadena de proveedores gratuitos con fallback local, reduce y devuelve claims
 * listos para proyectar.
 *
 * Task I5 acota el tiempo total: el trabajo remoto vive dentro de `budget.sessionMs` y cada
 * paquete dentro de `budget.packetMs`. Al vencer cualquier límite —o al fallar una
 * dependencia (caché, credenciales)— el paquete cae al fallback local **sin** propagar la
 * cancelación como falla de transcripción. El resultado es siempre un [InterpretationOutcome]
 * terminal: nunca lanza por deadline y nunca deja la sesión en un estado fantasma.
 *
 * - Procesa cada paquete por vez (nunca en paralelo) y comparte, entre paquetes, el circuito
 *   por ejecución y el contador de fallas transitorias.
 * - Reutiliza la caché de respuestas validadas del router; por eso reabrir o cambiar de modo
 *   no vuelve a llamar a la red (el modo se aplica al proyectar, fuera de acá).
 * - Si no hay proveedores habilitados, todos los paquetes caen al fallback local.
 */
class RouterSemanticInterpreter(
    private val packetBuilder: InterpretationPacketBuilder,
    private val router: FreeInferenceRouter,
    private val reducer: SemanticClaimReducer,
    private val fallback: FallbackClaimExtractor,
    private val enabledProviders: suspend () -> List<ProviderModel>,
    private val merger: HybridClaimMerger = HybridClaimMerger(),
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val journal: InterpretationJournal? = null,
    private val versions: InterpretationRunVersions = InterpretationRunVersions(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : SemanticInterpreter {

    override suspend fun interpret(
        sessionId: SessionId,
        spans: List<TranscriptSpan>,
        budget: InterpretationBudget,
        signals: LocalInterpretationSignals,
    ): InterpretationOutcome {
        val packets = packetBuilder.build(spans)
        if (packets.isEmpty()) return InterpretationOutcome.Remote(emptyList())

        val providers = try {
            enabledProviders()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }

        val runId = runIdFactory()
        val disabled = mutableSetOf<InferenceProvider>()
        val strikes = mutableMapOf<InferenceProvider, Int>()
        val merged = mutableListOf<EvidenceClaim>()
        val summaries = mutableListOf<String>()
        val processed = BooleanArray(packets.size)
        var anyLocal = false
        var anyRemote = false
        var failure: InterpretationFailure? = null

        // Telemetría de la corrida (Task I7b): registra corrida, paquetes e intentos. Nunca
        // deja que un fallo de la base rompa la interpretación (se traga con runCatching).
        journaled { journal?.beginRun(runRecord(runId, sessionId.value, spans)) }

        val completedInBudget = withTimeoutOrNull(budget.sessionMs) {
            packets.forEachIndexed { index, packet ->
                // Los candidatos locales se calculan siempre y se fusionan con lo remoto (Q3).
                val local = fallback.extract(packet.request.spans, signals)
                // Nivel 1: enriquece la request con pistas locales orientativas (números de
                // página/ejercicio y marcadores). El packetId incorpora la huella de las pistas
                // para que la caché no confunda dos corridas con pistas distintas.
                val request = packet.request.withHints(buildHints(packet.request.spans, local, signals))
                journaled {
                    journal?.startPacket(
                        runId = runId,
                        packetId = request.packetId,
                        ordinal = index,
                        requestHash = request.packetId,
                        requestBytes = request.spans.sumOf { it.text.toByteArray(Charsets.UTF_8).size },
                    )
                }
                var errored = false
                val remote = try {
                    withTimeoutOrNull(budget.packetMs) {
                        router.routeRemote(
                            sessionId = sessionId.value,
                            packet = request,
                            providers = providers,
                            disabledProviders = disabled,
                            runId = runId,
                            sourceSpanIds = packet.sourceSpanIds,
                            transientStrikes = strikes,
                            onAttempt = { info ->
                                journaled {
                                    journal?.recordAttempt(
                                        ProviderAttemptRecord(
                                            id = UUID.randomUUID().toString(),
                                            runId = runId,
                                            packetId = request.packetId,
                                            provider = info.provider,
                                            modelId = info.modelId,
                                            attempt = info.attempt,
                                            cacheHit = info.cacheHit,
                                            outcome = info.outcome,
                                            durationMs = info.durationMs,
                                            startedAtEpochMs = info.startedAtEpochMs,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    errored = true
                    null
                }
                when {
                    remote != null && remote.provider != null -> {
                        merged += merger.merge(local, remote.claims)
                        remote.summary?.takeIf { it.isNotBlank() }?.let { summaries += it }
                        anyRemote = true
                        journaled { journal?.completePacket(runId, request.packetId, InterpretationPacketState.REMOTE_OK, remote.provider) }
                    }
                    remote != null -> {
                        merged += merger.merge(local, emptyList())
                        anyLocal = true
                        failure = failure ?: remote.failures.toInterpretationFailure()
                        journaled { journal?.completePacket(runId, request.packetId, InterpretationPacketState.LOCAL_OK, null) }
                    }
                    else -> {
                        merged += merger.merge(local, emptyList())
                        anyLocal = true
                        failure = failure
                            ?: if (errored) InterpretationFailure.INTERNAL else InterpretationFailure.DEADLINE
                        journaled { journal?.completePacket(runId, request.packetId, InterpretationPacketState.FAILED, null) }
                    }
                }
                processed[index] = true
            }
            true
        }

        if (completedInBudget == null) {
            anyLocal = true
            failure = failure ?: InterpretationFailure.DEADLINE
            packets.forEachIndexed { index, packet ->
                if (!processed[index]) {
                    merged += merger.merge(fallback.extract(packet.request.spans, signals), emptyList())
                }
            }
        }

        val claims = reducer.reduceMerged(merged)
        val runState = when {
            !anyLocal -> InterpretationRunState.REMOTE_OK
            anyRemote -> InterpretationRunState.MIXED_OK
            else -> InterpretationRunState.LOCAL_OK
        }
        journaled { journal?.completeRun(runId, runState) }

        val summary = summaries.joinToString("\n\n").ifBlank { null }
        return if (anyLocal) {
            InterpretationOutcome.LocalOrMixed(claims, failure, summary)
        } else {
            InterpretationOutcome.Remote(claims, summary)
        }
    }

    /** Ejecuta una escritura de telemetría sin dejar que su falla rompa la interpretación. */
    private suspend inline fun journaled(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // La observabilidad es best-effort: nunca degrada la generación de la ficha.
        }
    }

    private fun runRecord(runId: String, sessionId: String, spans: List<TranscriptSpan>) = InterpretationRunRecord(
        id = runId,
        sessionId = sessionId,
        appVersion = versions.appVersion,
        whisperVersion = versions.whisperVersion,
        promptVersion = versions.promptVersion,
        schemaVersion = versions.schemaVersion,
        validatorVersion = versions.validatorVersion,
        transcriptHash = transcriptHash(spans),
        // La corrida es independiente del modo (el modo se aplica al proyectar). Se registra un
        // valor neutral solo para satisfacer el esquema.
        mode = InterpretationMode.CONSERVATIVE,
        startedAtEpochMs = nowEpochMs(),
    )

    /**
     * Arma las pistas locales orientativas del paquete (Nivel 1). Solo incluye datos de bajo
     * riesgo: números de página/ejercicio detectados por reglas locales y la posición (id de
     * fragmento) de cada marcador manual. Nunca incluye texto libre de la transcripción.
     */
    private fun buildHints(
        spans: List<PublicTranscriptSpan>,
        local: List<RawClaim>,
        signals: LocalInterpretationSignals,
    ): List<String> {
        val hints = LinkedHashSet<String>()
        local.asSequence()
            .filter { it.category == ClaimCategory.PAGE }
            .map { it.normalizedValue.trim() }
            .filter { it.isNotBlank() }
            .forEach { hints += "posible página $it" }
        local.asSequence()
            .filter { it.category == ClaimCategory.EXERCISE }
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .forEach { hints += "posible ejercicio $it" }
        signals.markers.forEach { marker ->
            nearestSpanId(spans, marker)?.let {
                hints += "marca de tarea del docente cerca del fragmento $it"
            }
        }
        return hints.toList()
    }

    /** Id público del fragmento que contiene el marcador; si ninguno, el último de contenido. */
    private fun nearestSpanId(spans: List<PublicTranscriptSpan>, marker: ManualMarkerSignal): String? {
        val content = spans.filterNot { it.contextOnly }
        if (content.isEmpty()) return null
        return (content.firstOrNull { marker.offsetMs in it.startMs..it.endMs } ?: content.last()).publicId
    }

    /**
     * Adjunta las pistas a la request y, si hay pistas, incorpora su huella al packetId para que
     * la caché distinga dos corridas con pistas distintas (p. ej. al agregarse un marcador).
     */
    private fun InterpretationRequest.withHints(hints: List<String>): InterpretationRequest =
        if (hints.isEmpty()) this else copy(hints = hints, packetId = "$packetId-h${hintDigest(hints)}")

    private fun hintDigest(hints: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(hints.joinToString("\n").toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun transcriptHash(spans: List<TranscriptSpan>): String {
        val canonical = spans.joinToString("\n") { "${it.id}|${it.startMs}-${it.endMs}|${it.text}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun List<ProviderFailure>.toInterpretationFailure(): InterpretationFailure? = when {
        isEmpty() -> null
        any { it == ProviderFailure.NO_NETWORK || it == ProviderFailure.SERVER_UNAVAILABLE || it == ProviderFailure.TIMEOUT } ->
            InterpretationFailure.TRANSPORT
        any { it == ProviderFailure.INVALID_RESPONSE || it == ProviderFailure.EMPTY_RESPONSE } ->
            InterpretationFailure.INVALID_RESPONSE
        any { it == ProviderFailure.AUTHENTICATION || it == ProviderFailure.BILLING_RISK || it == ProviderFailure.QUOTA || it == ProviderFailure.NOT_CONFIGURED } ->
            InterpretationFailure.INTERNAL
        else -> InterpretationFailure.INTERNAL
    }
}

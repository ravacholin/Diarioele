package com.capo.diarioclase.processing.work

import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.SegmentId
import com.capo.diarioclase.data.db.SegmentState
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.SessionState
import com.capo.diarioclase.data.db.TranscriptionCheckpointEntity
import com.capo.diarioclase.data.db.TranscriptionRunEntity
import com.capo.diarioclase.processing.evidence.ClaimReducer
import com.capo.diarioclase.processing.evidence.DiaryFieldMaterializer
import com.capo.diarioclase.processing.evidence.DiaryDraft
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.evidence.InterpretationProjector
import com.capo.diarioclase.processing.evidence.LiteralClaimExtractor
import com.capo.diarioclase.processing.evidence.PagesAndExercisesComposer
import com.capo.diarioclase.processing.transcription.AudioWindow
import com.capo.diarioclase.processing.transcription.AudioWindowPlan
import com.capo.diarioclase.processing.transcription.AudioWindowPlanner
import com.capo.diarioclase.processing.transcription.PcmWindowReader
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
import com.capo.diarioclase.processing.transcription.WindowTranscriptResult
import com.capo.diarioclase.processing.transcription.WindowTranscriptionEngine
import com.capo.diarioclase.recording.audio.ReadySegment
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class ProcessableSegment(
    val ready: ReadySegment,
    val ordinal: Int,
    val state: SegmentState,
)

enum class TranscriptionRunState {
    PREPARING,
    PROCESSING,
    PAUSED,
    COMPLETED,
    FAILED,
}

data class TranscriptionProgress(
    val sessionId: SessionId,
    val processedMs: Long,
    val totalMs: Long,
    val currentBlock: Int,
    val currentWindow: Int,
    val totalWindows: Int,
    val state: TranscriptionRunState,
    val failure: TranscriptionFailure?,
)

interface ProcessingStore {
    suspend fun sessionState(id: SessionId): SessionState
    suspend fun segments(id: SessionId): List<ProcessableSegment>
    suspend fun run(id: SessionId): TranscriptionRunEntity?
    suspend fun checkpoint(id: SegmentId): TranscriptionCheckpointEntity?
    suspend fun checkpoints(id: SessionId): List<TranscriptionCheckpointEntity>
    suspend fun saveRun(run: TranscriptionRunEntity)
    suspend fun markTranscribing(id: SegmentId)

    /**
     * Persiste el avance parcial de la ventana en curso (progreso en vivo). Por defecto es
     * un no-op para implementaciones que no lo necesitan.
     */
    suspend fun updateWindowProgress(run: TranscriptionRunEntity, processedMs: Long) = Unit

    suspend fun confirmWindow(
        segmentId: SegmentId,
        spans: List<TranscriptSpan>,
        checkpoint: TranscriptionCheckpointEntity,
        run: TranscriptionRunEntity,
        segmentComplete: Boolean,
    ): TranscriptionRunEntity
    suspend fun recordWindowFailure(
        segmentId: SegmentId,
        checkpoint: TranscriptionCheckpointEntity,
        run: TranscriptionRunEntity,
        failure: TranscriptionFailure,
    )
    suspend fun transcript(id: SessionId): List<TranscriptSpan>

    /**
     * Señales locales (marcadores manuales) que acompañan la fusión híbrida (Fase 6, Q7). No
     * viajan a un proveedor. Por defecto vacías para implementaciones que no las necesitan.
     */
    suspend fun loadSignals(id: SessionId): com.capo.diarioclase.processing.semantic.LocalInterpretationSignals =
        com.capo.diarioclase.processing.semantic.LocalInterpretationSignals()

    suspend fun draft(id: SessionId): DiaryDraftEntity?
    suspend fun saveEvidence(id: SessionId, claims: List<EvidenceClaim>, draft: DiaryDraft)
    suspend fun updateSession(id: SessionId, state: SessionState)
}

sealed interface ProcessingStepOutcome {
    data class WindowSaved(val progress: TranscriptionProgress) : ProcessingStepOutcome
    data class Complete(val draft: DiaryDraft) : ProcessingStepOutcome
    data class Paused(val progress: TranscriptionProgress) : ProcessingStepOutcome
    data class Failed(
        val segmentId: SegmentId,
        val failure: TranscriptionFailure,
        val retryable: Boolean,
    ) : ProcessingStepOutcome
}

sealed interface ProcessingOutcome {
    data class Complete(val draft: DiaryDraft) : ProcessingOutcome
    data class Failed(
        val segmentId: SegmentId,
        val failure: TranscriptionFailure,
        val retryable: Boolean,
    ) : ProcessingOutcome
}

class TranscriptionCoordinator(
    private val store: ProcessingStore,
    private val engine: WindowTranscriptionEngine,
    private val extractor: LiteralClaimExtractor = LiteralClaimExtractor(),
    private val reducer: ClaimReducer = ClaimReducer(),
    projector: InterpretationProjector = InterpretationProjector(),
    private val materializer: DiaryFieldMaterializer = DiaryFieldMaterializer(
        projector,
        PagesAndExercisesComposer(),
    ),
    private val pcmReader: (File, AudioWindowPlan) -> FloatArray = PcmWindowReader::read,
    private val interpreter: SemanticInterpreter? = null,
) {
    suspend fun process(
        sessionId: SessionId,
        mode: InterpretationMode,
    ): ProcessingOutcome {
        while (true) {
            when (val step = processNext(sessionId, mode)) {
                is ProcessingStepOutcome.WindowSaved -> Unit
                is ProcessingStepOutcome.Complete -> return ProcessingOutcome.Complete(step.draft)
                is ProcessingStepOutcome.Failed ->
                    return ProcessingOutcome.Failed(step.segmentId, step.failure, step.retryable)
                is ProcessingStepOutcome.Paused ->
                    return ProcessingOutcome.Failed(
                        SegmentId(step.progress.sessionId.value),
                        TranscriptionFailure.INTERRUPTED,
                        retryable = true,
                    )
            }
        }
    }

    suspend fun processNext(
        sessionId: SessionId,
        mode: InterpretationMode,
    ): ProcessingStepOutcome {
        val sessionState = store.sessionState(sessionId)
        if (sessionState == SessionState.FINALIZED) {
            store.updateSession(sessionId, SessionState.TRANSCRIBING)
        }

        val segments = store.segments(sessionId).sortedBy { it.ordinal }
        val totalMs = segments.sumOf { it.ready.durationMs }
        val windowCounts = segments.associate { segment ->
            segment.ready.id to AudioWindowPlanner.plan(segment.ready.durationMs).size
        }
        val totalWindows = windowCounts.values.sum()
        var run = store.run(sessionId) ?: TranscriptionRunEntity(
            sessionId = sessionId.value,
            state = TranscriptionRunState.PREPARING.name,
            pauseRequested = false,
            processedMs = 0,
            totalMs = totalMs,
            currentSegmentId = null,
            failure = null,
            updatedAtEpochMs = 0,
        )
        run = run.copy(totalMs = totalMs)

        if (run.pauseRequested) {
            val pausedRun = run.copy(state = TranscriptionRunState.PAUSED.name)
            store.saveRun(pausedRun)
            return ProcessingStepOutcome.Paused(
                progress(
                    sessionId,
                    pausedRun,
                    segments,
                    store.checkpoints(sessionId),
                    totalWindows,
                    null,
                ),
            )
        }

        val knownCheckpoints = store.checkpoints(sessionId)
        for (segment in segments) {
            if (segment.state == SegmentState.TRANSCRIBED) continue

            val plans = AudioWindowPlanner.plan(segment.ready.durationMs)
            if (plans.isEmpty()) {
                return fail(
                    sessionId,
                    segment,
                    run,
                    knownCheckpoints,
                    totalWindows,
                    null,
                    TranscriptionFailure.INVALID_AUDIO,
                    retryable = false,
                )
            }

            val existingCheckpoint = store.checkpoint(segment.ready.id)
            val confirmedUntilMs = existingCheckpoint?.confirmedUntilMs ?: 0L
            val plan = plans.firstOrNull { it.confirmedUntilMs > confirmedUntilMs }

            if (plan == null) {
                val repairedCheckpoint = requireNotNull(existingCheckpoint).copy(
                    state = TranscriptionRunState.COMPLETED.name,
                    failure = null,
                )
                val savedRun = store.confirmWindow(
                    segment.ready.id,
                    emptyList(),
                    repairedCheckpoint,
                    run.copy(
                        state = TranscriptionRunState.PROCESSING.name,
                        currentSegmentId = segment.ready.id.value,
                        failure = null,
                    ),
                    segmentComplete = true,
                )
                return ProcessingStepOutcome.WindowSaved(
                    progress(
                        sessionId,
                        savedRun,
                        segments,
                        replaceCheckpoint(knownCheckpoints, repairedCheckpoint),
                        totalWindows,
                        segment,
                    ),
                )
            }

            if (segment.state != SegmentState.TRANSCRIBING) {
                store.markTranscribing(segment.ready.id)
            }

            val checkpoint = existingCheckpoint ?: TranscriptionCheckpointEntity(
                audioSegmentId = segment.ready.id.value,
                sessionId = sessionId.value,
                confirmedUntilMs = 0,
                totalMs = segment.ready.durationMs,
                processedWindows = 0,
                totalWindows = plans.size,
                state = TranscriptionRunState.PREPARING.name,
                failure = null,
                updatedAtEpochMs = 0,
            )
            val processingRun = run.copy(
                state = TranscriptionRunState.PROCESSING.name,
                currentSegmentId = segment.ready.id.value,
                failure = null,
            )
            store.saveRun(processingRun)
            val samples = try {
                pcmReader(File(segment.ready.path), plan)
            } catch (_: Throwable) {
                return fail(
                    sessionId,
                    segment,
                    processingRun,
                    knownCheckpoints,
                    totalWindows,
                    checkpoint,
                    TranscriptionFailure.INVALID_AUDIO,
                    retryable = false,
                )
            }

            val window = AudioWindow(
                segmentId = segment.ready.id,
                blockId = segment.ready.blockId,
                index = plan.index,
                startMs = plan.startMs,
                endMs = plan.endMs,
                samples = samples,
            )
            val windowSpanMs = (plan.confirmedUntilMs - confirmedUntilMs).coerceAtLeast(0L)
            val baseProcessedMs = processingRun.processedMs
            val result = coroutineScope {
                val progressChannel = Channel<Int>(Channel.CONFLATED)
                launch {
                    var lastPercent = -1
                    for (percent in progressChannel) {
                        if (percent <= lastPercent) continue
                        lastPercent = percent
                        val partial = (baseProcessedMs + windowSpanMs * percent / 100)
                            .coerceIn(baseProcessedMs, run.totalMs)
                        runCatching { store.updateWindowProgress(processingRun, partial) }
                    }
                }
                try {
                    engine.transcribe(window) { percent ->
                        progressChannel.trySend(percent.coerceIn(0, 100))
                    }
                } finally {
                    progressChannel.close()
                }
            }
            when (result) {
                is WindowTranscriptResult.Success -> {
                    val segmentComplete = plan.endMs >= segment.ready.durationMs
                    val savedCheckpoint = checkpoint.copy(
                        confirmedUntilMs = plan.confirmedUntilMs,
                        processedWindows = plan.index + 1,
                        state = if (segmentComplete) {
                            TranscriptionRunState.COMPLETED.name
                        } else {
                            TranscriptionRunState.PROCESSING.name
                        },
                        failure = null,
                    )
                    val savedRun = store.confirmWindow(
                        segment.ready.id,
                        result.spans,
                        savedCheckpoint,
                        processingRun,
                        segmentComplete,
                    )
                    return ProcessingStepOutcome.WindowSaved(
                        progress(
                            sessionId,
                            savedRun,
                            segments,
                            replaceCheckpoint(knownCheckpoints, savedCheckpoint),
                            totalWindows,
                            segment,
                        ),
                    )
                }

                is WindowTranscriptResult.Failure -> {
                    return fail(
                        sessionId,
                        segment,
                        processingRun,
                        knownCheckpoints,
                        totalWindows,
                        checkpoint,
                        result.code,
                        result.retryable,
                    )
                }
            }
        }

        if (store.sessionState(sessionId) == SessionState.TRANSCRIBING) {
            store.updateSession(sessionId, SessionState.EXTRACTING)
        }
        val transcript = store.transcript(sessionId)
        // La interpretación remota (router + fallback local) reemplaza a la extracción
        // directa cuando hay un intérprete compuesto; el modo se aplica siempre localmente
        // al proyectar, sin volver a llamar a la red.
        val claims = interpreter?.interpret(sessionId, transcript, signals = store.loadSignals(sessionId))?.claims
            ?: reducer.reduce(extractor.extract(transcript))
        val generatedDraft = materializer.materialize(sessionId.value, mode, claims)
        val draft = store.draft(sessionId)?.takeIf { it.userEdited }?.let { edited ->
            generatedDraft.copy(
                topics = edited.topics,
                activities = edited.activities,
                pages = edited.pages,
                exercises = edited.exercises,
                homework = edited.homework,
            )
        } ?: generatedDraft
        store.saveEvidence(sessionId, claims, draft)
        store.updateSession(sessionId, SessionState.AWAITING_REVIEW)
        store.saveRun(
            run.copy(
                state = TranscriptionRunState.COMPLETED.name,
                pauseRequested = false,
                processedMs = totalMs,
                totalMs = totalMs,
                currentSegmentId = null,
                failure = null,
            ),
        )
        return ProcessingStepOutcome.Complete(draft)
    }

    suspend fun failCurrent(
        sessionId: SessionId,
        failure: TranscriptionFailure,
        retryable: Boolean,
    ): ProcessingStepOutcome.Failed? {
        val run = store.run(sessionId) ?: return null
        val segmentId = run.currentSegmentId?.let(::SegmentId) ?: return null
        val segments = store.segments(sessionId).sortedBy { it.ordinal }
        val segment = segments.firstOrNull { it.ready.id == segmentId } ?: return null
        return fail(
            sessionId = sessionId,
            segment = segment,
            run = run,
            knownCheckpoints = store.checkpoints(sessionId),
            totalWindows = segments.sumOf {
                AudioWindowPlanner.plan(it.ready.durationMs).size
            },
            checkpoint = store.checkpoint(segmentId),
            failure = failure,
            retryable = retryable,
        )
    }

    private suspend fun fail(
        sessionId: SessionId,
        segment: ProcessableSegment,
        run: TranscriptionRunEntity,
        knownCheckpoints: List<TranscriptionCheckpointEntity>,
        totalWindows: Int,
        checkpoint: TranscriptionCheckpointEntity?,
        failure: TranscriptionFailure,
        retryable: Boolean,
    ): ProcessingStepOutcome.Failed {
        val savedCheckpoint = checkpoint ?: TranscriptionCheckpointEntity(
            audioSegmentId = segment.ready.id.value,
            sessionId = sessionId.value,
            confirmedUntilMs = 0,
            totalMs = segment.ready.durationMs,
            processedWindows = 0,
            totalWindows = AudioWindowPlanner.plan(segment.ready.durationMs).size,
            state = TranscriptionRunState.FAILED.name,
            failure = failure.name,
            updatedAtEpochMs = 0,
        )
        store.recordWindowFailure(segment.ready.id, savedCheckpoint, run, failure)
        return ProcessingStepOutcome.Failed(segment.ready.id, failure, retryable)
    }

    private fun progress(
        sessionId: SessionId,
        run: TranscriptionRunEntity,
        segments: List<ProcessableSegment>,
        checkpoints: List<TranscriptionCheckpointEntity>,
        totalWindows: Int,
        currentSegment: ProcessableSegment?,
    ) = TranscriptionProgress(
        sessionId = sessionId,
        processedMs = run.processedMs,
        totalMs = run.totalMs,
        currentBlock = currentSegment?.ordinal?.plus(1) ?: 0,
        currentWindow = checkpoints.sumOf { it.processedWindows },
        totalWindows = totalWindows,
        state = TranscriptionRunState.valueOf(run.state),
        failure = run.failure?.let { runCatching { TranscriptionFailure.valueOf(it) }.getOrNull() },
    )

    private fun replaceCheckpoint(
        checkpoints: List<TranscriptionCheckpointEntity>,
        updated: TranscriptionCheckpointEntity,
    ) = checkpoints.filterNot { it.audioSegmentId == updated.audioSegmentId } + updated

}

package com.capo.diarioclase.processing.work

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.SegmentId
import com.capo.diarioclase.data.db.SegmentState
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.SessionState
import com.capo.diarioclase.data.db.TranscriptionCheckpointEntity
import com.capo.diarioclase.data.db.TranscriptionRunEntity
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.DiaryDraft
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.evidence.LiteralClaimExtractor
import com.capo.diarioclase.processing.evidence.PagesAndExercisesComposer
import com.capo.diarioclase.processing.transcription.AudioWindow
import com.capo.diarioclase.processing.transcription.TranscriptDeduplicator
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
import com.capo.diarioclase.processing.transcription.WindowTranscriptResult
import com.capo.diarioclase.processing.transcription.WindowTranscriptionEngine
import com.capo.diarioclase.recording.audio.ReadySegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionCoordinatorTest {
    @Test
    fun `checkpoints every window and creates five field draft`() = runTest {
        val store = FakeStore()
        val engine = RecordingWindowEngine { window ->
            val text = if (window.segmentId.value == "a") {
                "Vamos a la página cuarenta y dos"
            } else {
                "Hacemos el ejercicio tres"
            }
            WindowTranscriptResult.Success(listOf(span(window, text)))
        }

        val result = coordinator(store, engine).process(
            SessionId("day"),
            InterpretationMode.CONSERVATIVE,
        )

        assertTrue(result is ProcessingOutcome.Complete)
        assertEquals(listOf("a", "b"), store.completedSegments)
        assertEquals("42 (3)", store.generatedDraft?.pages)
        assertEquals("", store.generatedDraft?.exercises)
        assertEquals(SessionState.AWAITING_REVIEW, store.state)
        assertEquals(TranscriptionRunState.COMPLETED.name, store.savedRun?.state)
    }

    @Test
    fun `pages and exercises are combined into one grouped field`() {
        val ev = EvidenceRef(BlockId("b"), 0, 1, "x")
        fun claim(id: String, category: ClaimCategory, value: String, normalized: String = value) =
            EvidenceClaim(id, category, value, normalized, ClaimStatus.PERFORMED, .95, ClaimOrigin.SEMANTIC, ev)
        val claims = listOf(
            claim("p1", ClaimCategory.PAGE, "página 14", "14"),
            claim("e1", ClaimCategory.EXERCISE, "3"),
            claim("e2", ClaimCategory.EXERCISE, "a"),
            claim("e3", ClaimCategory.EXERCISE, "b"),
            claim("e4", ClaimCategory.EXERCISE, "8"),
            claim("p2", ClaimCategory.PAGE, "22", "22"),
            claim("e5", ClaimCategory.EXERCISE, "1"),
            claim("e6", ClaimCategory.EXERCISE, "2"),
        )

        val text = PagesAndExercisesComposer().compose(claims)

        assertEquals("14 (3, a, b, 8)\n22 (1, 2)", text)
    }

    @Test
    fun `semantic assigned exercise is materialized as homework`() = runTest {
        val store = FakeStore(durations = linkedMapOf("a" to 1_000L))
        val engine = RecordingWindowEngine {
            WindowTranscriptResult.Success(listOf(span(it, "Ejercicio cuatro para mañana")))
        }
        val evidence = EvidenceRef(BlockId("block"), 0, 1_000, "ejercicio cuatro")
        val interpreter = object : SemanticInterpreter {
            override suspend fun interpret(
                sessionId: SessionId,
                spans: List<TranscriptSpan>,
                budget: InterpretationBudget,
            ) = InterpretationOutcome.Remote(
                listOf(
                    EvidenceClaim(
                        id = "e1",
                        category = ClaimCategory.EXERCISE,
                        value = "4",
                        normalizedValue = "4",
                        status = ClaimStatus.ASSIGNED,
                        confidence = 1.0,
                        origin = ClaimOrigin.GEMINI,
                        evidence = evidence,
                    ),
                ),
            )
        }

        val result = coordinator(store, engine, interpreter).process(
            SessionId("day"),
            InterpretationMode.CONSERVATIVE,
        ) as ProcessingOutcome.Complete

        assertEquals("4", result.draft.homework)
        assertEquals("", result.draft.pages)
        assertEquals("", result.draft.exercises)
    }

    @Test
    fun `live window progress is persisted as partial processed time`() = runTest {
        val store = FakeStore(durations = linkedMapOf("a" to 1_000L))
        val engine = object : WindowTranscriptionEngine {
            override suspend fun transcribe(window: AudioWindow) = transcribe(window) {}
            override suspend fun transcribe(
                window: AudioWindow,
                onProgress: (Int) -> Unit,
            ): WindowTranscriptResult {
                onProgress(50)
                onProgress(100)
                return WindowTranscriptResult.Success(listOf(span(window, "Página diez")))
            }
        }

        coordinator(store, engine).process(SessionId("day"), InterpretationMode.CONSERVATIVE)

        assertTrue(store.windowProgress.isNotEmpty())
        assertEquals(1_000L, store.windowProgress.last())
    }

    @Test
    fun `confirmed window is not transcribed twice after restart`() = runTest {
        val store = FakeStore(durations = linkedMapOf("a" to 65_000L))
        store.savedRun = runEntity(processedMs = 28_000, totalMs = 65_000)
        store.savedCheckpoints["a"] = checkpoint(
            segment = "a",
            confirmedUntilMs = 28_000,
            processedWindows = 1,
            totalWindows = 3,
            totalMs = 65_000,
        )
        val engine = RecordingWindowEngine {
            WindowTranscriptResult.Success(listOf(span(it, "Ejercicio tres")))
        }

        val outcome = coordinator(store, engine).processNext(
            SessionId("day"),
            InterpretationMode.CONSERVATIVE,
        )

        assertEquals(28_000, engine.received.single().startMs)
        assertTrue(outcome is ProcessingStepOutcome.WindowSaved)
        assertEquals(56_000, store.savedCheckpoints.getValue("a").confirmedUntilMs)
    }

    @Test
    fun `window failure preserves prior transcript and checkpoint`() = runTest {
        val store = FakeStore(durations = linkedMapOf("a" to 65_000L))
        val prior = TranscriptSpan("prior", "a", BlockId("block"), 0, 28_000, "Página doce", .9)
        store.persistedSpans += prior
        store.savedRun = runEntity(processedMs = 28_000, totalMs = 65_000)
        store.savedCheckpoints["a"] = checkpoint(
            segment = "a",
            confirmedUntilMs = 28_000,
            processedWindows = 1,
            totalWindows = 3,
            totalMs = 65_000,
        )
        val engine = RecordingWindowEngine {
            WindowTranscriptResult.Failure(TranscriptionFailure.TIMEOUT, retryable = true)
        }

        val outcome = coordinator(store, engine).processNext(
            SessionId("day"),
            InterpretationMode.CONSERVATIVE,
        )

        assertTrue(outcome is ProcessingStepOutcome.Failed)
        assertEquals(listOf(prior), store.persistedSpans)
        assertEquals(28_000, store.savedCheckpoints.getValue("a").confirmedUntilMs)
        assertEquals(TranscriptionFailure.TIMEOUT.name, store.savedRun?.failure)
    }

    @Test
    fun `durable pause stops before reading or transcribing another window`() = runTest {
        val store = FakeStore(durations = linkedMapOf("a" to 65_000L))
        store.savedRun = runEntity(
            processedMs = 28_000,
            totalMs = 65_000,
            pauseRequested = true,
        )
        val engine = RecordingWindowEngine {
            WindowTranscriptResult.Success(emptyList())
        }

        val outcome = coordinator(store, engine).processNext(
            SessionId("day"),
            InterpretationMode.CONSERVATIVE,
        )

        assertTrue(outcome is ProcessingStepOutcome.Paused)
        assertTrue(engine.received.isEmpty())
        assertEquals(TranscriptionRunState.PAUSED.name, store.savedRun?.state)
    }

    @Test
    fun `user edit survives mode change`() = runTest {
        val store = FakeStore().apply {
            existingDraft = DiaryDraftEntity(
                "draft",
                "day",
                InterpretationMode.CONSERVATIVE.name,
                "Tema manual",
                "Actividad manual",
                "12",
                "3",
                "Tarea manual",
                1_000,
                true,
            )
        }
        val engine = RecordingWindowEngine {
            WindowTranscriptResult.Success(listOf(span(it, "Página diez")))
        }

        val result = coordinator(store, engine).process(
            SessionId("day"),
            InterpretationMode.EXHAUSTIVE,
        )

        val draft = (result as ProcessingOutcome.Complete).draft
        assertEquals(InterpretationMode.EXHAUSTIVE, draft.mode)
        assertEquals("Tema manual", draft.topics)
        assertEquals("Actividad manual", draft.activities)
        assertEquals("12", draft.pages)
        assertEquals("3", draft.exercises)
        assertEquals("Tarea manual", draft.homework)
    }

    private fun coordinator(
        store: FakeStore,
        engine: WindowTranscriptionEngine,
        interpreter: SemanticInterpreter? = null,
    ) = TranscriptionCoordinator(
        store = store,
        engine = engine,
        extractor = LiteralClaimExtractor { store.nextId() },
        pcmReader = { _, plan ->
            FloatArray(((plan.endMs - plan.startMs) * 16).toInt())
        },
        interpreter = interpreter,
    )

    private fun span(window: AudioWindow, text: String) = TranscriptSpan(
        id = "span-" + text.hashCode() + "-" + window.startMs,
        audioSegmentId = window.segmentId.value,
        blockId = window.blockId,
        startMs = window.startMs,
        endMs = window.endMs,
        text = text,
        confidence = .9,
    )

    private fun runEntity(
        processedMs: Long,
        totalMs: Long,
        pauseRequested: Boolean = false,
    ) = TranscriptionRunEntity(
        sessionId = "day",
        state = TranscriptionRunState.PROCESSING.name,
        pauseRequested = pauseRequested,
        processedMs = processedMs,
        totalMs = totalMs,
        currentSegmentId = "a",
        failure = null,
        updatedAtEpochMs = 1,
    )

    private fun checkpoint(
        segment: String,
        confirmedUntilMs: Long,
        processedWindows: Int,
        totalWindows: Int,
        totalMs: Long,
    ) = TranscriptionCheckpointEntity(
        audioSegmentId = segment,
        sessionId = "day",
        confirmedUntilMs = confirmedUntilMs,
        totalMs = totalMs,
        processedWindows = processedWindows,
        totalWindows = totalWindows,
        state = TranscriptionRunState.PROCESSING.name,
        failure = null,
        updatedAtEpochMs = 1,
    )
}

private class RecordingWindowEngine(
    private val answer: (AudioWindow) -> WindowTranscriptResult,
) : WindowTranscriptionEngine {
    val received = mutableListOf<AudioWindow>()

    override suspend fun transcribe(window: AudioWindow): WindowTranscriptResult {
        received += window
        return answer(window)
    }
}

private class FakeStore(
    private val durations: LinkedHashMap<String, Long> =
        linkedMapOf("a" to 1_000L, "b" to 1_000L),
) : ProcessingStore {
    var state = SessionState.FINALIZED
    var counter = 0
    val completedSegments = mutableListOf<String>()
    val failedSegments = mutableListOf<String>()
    val persistedSpans = mutableListOf<TranscriptSpan>()
    val savedCheckpoints = linkedMapOf<String, TranscriptionCheckpointEntity>()
    val segmentStates = durations.keys.associateWith { SegmentState.READY }.toMutableMap()
    var savedRun: TranscriptionRunEntity? = null
    var generatedDraft: DiaryDraft? = null
    var existingDraft: DiaryDraftEntity? = null
    val windowProgress = mutableListOf<Long>()

    fun nextId() = "claim-" + counter++

    override suspend fun sessionState(id: SessionId) = state

    override suspend fun segments(id: SessionId) =
        durations.entries.mapIndexed { index, entry ->
            ProcessableSegment(
                ReadySegment(
                    SegmentId(entry.key),
                    BlockId("block"),
                    "/" + entry.key + ".wav",
                    entry.value,
                    "hash",
                ),
                index,
                segmentStates.getValue(entry.key),
            )
        }

    override suspend fun run(id: SessionId) = savedRun

    override suspend fun checkpoint(id: SegmentId) = savedCheckpoints[id.value]

    override suspend fun checkpoints(id: SessionId) = savedCheckpoints.values.toList()

    override suspend fun saveRun(run: TranscriptionRunEntity) {
        savedRun = run
    }

    override suspend fun markTranscribing(id: SegmentId) {
        segmentStates[id.value] = SegmentState.TRANSCRIBING
    }

    override suspend fun updateWindowProgress(run: TranscriptionRunEntity, processedMs: Long) {
        windowProgress += processedMs
        savedRun = run.copy(processedMs = processedMs)
    }

    override suspend fun confirmWindow(
        segmentId: SegmentId,
        spans: List<TranscriptSpan>,
        checkpoint: TranscriptionCheckpointEntity,
        run: TranscriptionRunEntity,
        segmentComplete: Boolean,
    ): TranscriptionRunEntity {
        val existing = persistedSpans.filter { it.audioSegmentId == segmentId.value }
        persistedSpans.removeAll(existing.toSet())
        persistedSpans += TranscriptDeduplicator.merge(existing, spans)
        savedCheckpoints[segmentId.value] = checkpoint
        val processedMs = savedCheckpoints.values.sumOf { it.confirmedUntilMs }
        val updatedRun = run.copy(processedMs = processedMs)
        savedRun = updatedRun
        if (segmentComplete) {
            segmentStates[segmentId.value] = SegmentState.TRANSCRIBED
            completedSegments += segmentId.value
        }
        return updatedRun
    }

    override suspend fun recordWindowFailure(
        segmentId: SegmentId,
        checkpoint: TranscriptionCheckpointEntity,
        run: TranscriptionRunEntity,
        failure: TranscriptionFailure,
    ) {
        savedCheckpoints[segmentId.value] = checkpoint.copy(
            state = TranscriptionRunState.FAILED.name,
            failure = failure.name,
        )
        savedRun = run.copy(
            state = TranscriptionRunState.FAILED.name,
            failure = failure.name,
        )
        segmentStates[segmentId.value] = SegmentState.FAILED
        failedSegments += segmentId.value
    }

    override suspend fun transcript(id: SessionId) =
        persistedSpans.sortedWith(compareBy({ it.audioSegmentId }, { it.startMs }))

    override suspend fun draft(id: SessionId) = existingDraft

    override suspend fun saveEvidence(
        id: SessionId,
        claims: List<EvidenceClaim>,
        draft: DiaryDraft,
    ) {
        generatedDraft = draft
    }

    override suspend fun updateSession(id: SessionId, state: SessionState) {
        this.state = state
    }
}

package com.capo.diarioclase.processing.work

import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.processing.evidence.*
import com.capo.diarioclase.processing.transcription.*
import com.capo.diarioclase.recording.audio.ReadySegment

data class ProcessableSegment(val ready: ReadySegment, val ordinal: Int, val state: SegmentState)
interface ProcessingStore {
    suspend fun sessionState(id: SessionId): SessionState
    suspend fun segments(id: SessionId): List<ProcessableSegment>
    suspend fun markTranscribing(id: SegmentId)
    suspend fun saveTranscript(id: SegmentId, spans: List<TranscriptSpan>)
    suspend fun markFailed(id: SegmentId, failure: TranscriptionFailure)
    suspend fun transcript(id: SessionId): List<TranscriptSpan>
    suspend fun draft(id: SessionId): DiaryDraftEntity?
    suspend fun saveEvidence(id: SessionId, claims: List<EvidenceClaim>, draft: DiaryDraft)
    suspend fun updateSession(id: SessionId, state: SessionState)
}
sealed interface ProcessingOutcome {
    data class Complete(val draft: DiaryDraft) : ProcessingOutcome
    data class Failed(val segmentId: SegmentId, val failure: TranscriptionFailure, val retryable: Boolean) : ProcessingOutcome
}

class TranscriptionCoordinator(
    private val store: ProcessingStore,
    private val engine: TranscriptionEngine,
    private val extractor: LiteralClaimExtractor = LiteralClaimExtractor(),
    private val reducer: ClaimReducer = ClaimReducer(),
    private val projector: InterpretationProjector = InterpretationProjector(),
) {
    suspend fun process(sessionId: SessionId, mode: InterpretationMode): ProcessingOutcome {
        if (store.sessionState(sessionId) == SessionState.FINALIZED) store.updateSession(sessionId, SessionState.TRANSCRIBING)
        for (segment in store.segments(sessionId).sortedBy { it.ordinal }) {
            if (segment.state == SegmentState.TRANSCRIBED) continue
            store.markTranscribing(segment.ready.id)
            when (val result = engine.transcribe(segment.ready)) {
                is TranscriptResult.Success -> store.saveTranscript(segment.ready.id, result.spans)
                is TranscriptResult.Failure -> {
                    store.markFailed(segment.ready.id, result.code)
                    return ProcessingOutcome.Failed(segment.ready.id, result.code, result.retryable)
                }
            }
        }
        if (store.sessionState(sessionId) == SessionState.TRANSCRIBING) store.updateSession(sessionId, SessionState.EXTRACTING)
        val claims = reducer.reduce(extractor.extract(store.transcript(sessionId)))
        val presentation = projector.project(claims, mode)
        val generatedDraft = DiaryDraft(
            sessionId.value, mode,
            values(presentation.accepted, ClaimCategory.TOPIC),
            values(presentation.accepted, ClaimCategory.ACTIVITY),
            values(presentation.accepted, ClaimCategory.PAGE),
            values(presentation.accepted, ClaimCategory.EXERCISE),
            values(presentation.accepted, ClaimCategory.HOMEWORK),
            presentation.accepted, presentation.confirm,
        )
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
        return ProcessingOutcome.Complete(draft)
    }

    private fun values(claims: List<EvidenceClaim>, category: ClaimCategory) = claims.filter { it.category == category }.joinToString("\n") { it.value }.trim()
}

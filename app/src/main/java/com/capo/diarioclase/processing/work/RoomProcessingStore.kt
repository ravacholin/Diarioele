package com.capo.diarioclase.processing.work

import androidx.room.withTransaction
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.processing.evidence.*
import com.capo.diarioclase.processing.transcription.*
import com.capo.diarioclase.recording.audio.ReadySegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomProcessingStore(private val database: DiarioDatabase, private val clock: Clock) : ProcessingStore {
    private val dao = database.sessions()
    override suspend fun sessionState(id: SessionId) = SessionState.valueOf(requireNotNull(dao.session(id.value)).state)
    override suspend fun segments(id: SessionId) = dao.processingSegments(id.value).mapIndexed { index, entity ->
        ProcessableSegment(ReadySegment(SegmentId(entity.id), BlockId(entity.blockId), entity.path, entity.durationMs, entity.sha256.orEmpty(), SegmentState.valueOf(entity.state)), index, SegmentState.valueOf(entity.state))
    }
    override suspend fun markTranscribing(id: SegmentId) = dao.markTranscribing(id.value)
    override suspend fun saveTranscript(id: SegmentId, spans: List<TranscriptSpan>) = database.withTransaction {
        dao.deleteTranscript(id.value)
        dao.insertTranscript(spans.map { TranscriptSpanEntity(it.id, it.audioSegmentId, it.blockId.value, it.startMs, it.endMs, it.text, it.confidence) })
        dao.markTranscribed(id.value)
    }
    override suspend fun markFailed(id: SegmentId, failure: TranscriptionFailure) = dao.markTranscriptionFailed(id.value, failure.name)
    override suspend fun transcript(id: SessionId) = dao.transcript(id.value).map { TranscriptSpan(it.id, it.audioSegmentId, BlockId(it.blockId), it.startMs, it.endMs, it.text, it.confidence) }
    override suspend fun saveEvidence(id: SessionId, claims: List<EvidenceClaim>, draft: DiaryDraft) = database.withTransaction {
        val existingDraft = dao.draft(id.value)
        dao.deleteMachineClaims(id.value)
        dao.insertClaims(claims.map { EvidenceClaimEntity(it.id,id.value,it.category.name,it.value,it.normalizedValue,it.status.name,it.confidence,it.origin.name,it.evidence.blockId.value,it.evidence.startMs,it.evidence.endMs,it.evidence.excerpt,it.active) })
        val fields = existingDraft?.takeIf { it.userEdited }
        dao.saveDraft(DiaryDraftEntity(id.value,id.value,draft.mode.name,fields?.topics?:draft.topics,fields?.activities?:draft.activities,fields?.pages?:draft.pages,fields?.exercises?:draft.exercises,fields?.homework?:draft.homework,clock.nowEpochMs(),fields?.userEdited?:false))
    }
    override suspend fun updateSession(id: SessionId, state: SessionState) {
        val current = requireNotNull(dao.session(id.value))
        dao.updateSession(current.copy(state = state.name, updatedAtEpochMs = clock.nowEpochMs()))
    }
    fun observeLatestDraft(): Flow<DiaryDraftEntity?> = dao.observeLatestDraft()
    fun observeLatestClaims(): Flow<List<EvidenceClaim>> = dao.observeLatestClaims().map { rows -> rows.map { it.toDomain() } }
    fun observeClaims(sessionId: String): Flow<List<EvidenceClaim>> = dao.observeClaims(sessionId).map { rows -> rows.map { it.toDomain() } }
    override suspend fun draft(id: SessionId) = dao.draft(id.value)
    suspend fun saveEditedDraft(draft: DiaryDraftEntity) = dao.saveDraft(draft.copy(updatedAtEpochMs=clock.nowEpochMs(),userEdited=true))
    private fun EvidenceClaimEntity.toDomain() = EvidenceClaim(id,ClaimCategory.valueOf(category),value,normalizedValue,ClaimStatus.valueOf(status),confidence,ClaimOrigin.valueOf(origin),EvidenceRef(BlockId(blockId),startMs,endMs,excerpt),active)
}

package com.capo.diarioclase.processing.work

import androidx.room.withTransaction
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.AudioSegmentEntity
import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.EvidenceClaimEntity
import com.capo.diarioclase.data.db.SegmentId
import com.capo.diarioclase.data.db.SegmentState
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.SessionState
import com.capo.diarioclase.data.db.TranscriptSpanEntity
import com.capo.diarioclase.data.db.TranscriptionCheckpointEntity
import com.capo.diarioclase.data.db.TranscriptionRunEntity
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.DiaryDraft
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.transcription.TranscriptDeduplicator
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
import com.capo.diarioclase.recording.audio.ReadySegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomProcessingStore(
    private val database: DiarioDatabase,
    private val clock: Clock,
) : ProcessingStore, LocalReprojectionStore {
    private val dao = database.sessions()

    override suspend fun persistedClaims(sessionId: SessionId): List<EvidenceClaim> =
        dao.claimsSnapshot(sessionId.value).map { it.toDomain() }

    /**
     * Guarda la ficha reproyectada respetando una edición previa del docente: si la ficha
     * almacenada está marcada como editada, sus campos ganan; siempre se actualiza el modo.
     * No reescribe claims: la reproyección de Task I7 parte de los ya persistidos.
     */
    override suspend fun mergeFieldEditsAndSave(draft: DiaryDraft): DiaryDraft = database.withTransaction {
        val edited = dao.draft(draft.sessionId)?.takeIf { it.userEdited }
        val merged = DiaryDraftEntity(
            id = draft.sessionId,
            sessionId = draft.sessionId,
            mode = draft.mode.name,
            topics = edited?.topics ?: draft.topics,
            activities = edited?.activities ?: draft.activities,
            pages = edited?.pages ?: draft.pages,
            exercises = edited?.exercises ?: draft.exercises,
            homework = edited?.homework ?: draft.homework,
            updatedAtEpochMs = clock.nowEpochMs(),
            userEdited = edited?.userEdited ?: false,
        )
        dao.saveDraft(merged)
        draft.copy(
            topics = merged.topics,
            activities = merged.activities,
            pages = merged.pages,
            exercises = merged.exercises,
            homework = merged.homework,
        )
    }

    override suspend fun sessionState(id: SessionId) =
        SessionState.valueOf(requireNotNull(dao.session(id.value)).state)

    override suspend fun segments(id: SessionId) =
        dao.processingSegments(id.value).mapIndexed { index, entity ->
            ProcessableSegment(
                ready = ReadySegment(
                    SegmentId(entity.id),
                    BlockId(entity.blockId),
                    entity.path,
                    entity.durationMs,
                    entity.sha256.orEmpty(),
                    SegmentState.valueOf(entity.state),
                ),
                ordinal = index,
                state = SegmentState.valueOf(entity.state),
            )
        }

    override suspend fun run(id: SessionId): TranscriptionRunEntity? =
        dao.transcriptionRun(id.value)

    override suspend fun checkpoint(id: SegmentId): TranscriptionCheckpointEntity? =
        dao.checkpoint(id.value)

    override suspend fun checkpoints(id: SessionId): List<TranscriptionCheckpointEntity> =
        dao.checkpoints(id.value)

    override suspend fun saveRun(run: TranscriptionRunEntity) {
        dao.saveTranscriptionRun(run.copy(updatedAtEpochMs = clock.nowEpochMs()))
    }

    override suspend fun markTranscribing(id: SegmentId) =
        dao.markTranscribing(id.value)

    override suspend fun updateWindowProgress(run: TranscriptionRunEntity, processedMs: Long) {
        dao.saveTranscriptionRun(run.copy(processedMs = processedMs, updatedAtEpochMs = clock.nowEpochMs()))
    }

    override suspend fun confirmWindow(
        segmentId: SegmentId,
        spans: List<TranscriptSpan>,
        checkpoint: TranscriptionCheckpointEntity,
        run: TranscriptionRunEntity,
        segmentComplete: Boolean,
    ): TranscriptionRunEntity = database.withTransaction {
        val existing = dao.transcriptForSegment(segmentId.value).map { it.toDomain() }
        val merged = TranscriptDeduplicator.merge(existing, spans)
        dao.deleteTranscript(segmentId.value)
        if (merged.isNotEmpty()) {
            dao.insertTranscript(merged.map { it.toEntity() })
        }

        val now = clock.nowEpochMs()
        dao.saveCheckpoint(checkpoint.copy(updatedAtEpochMs = now))
        val processedMs = dao.checkpoints(run.sessionId)
            .sumOf { it.confirmedUntilMs.coerceAtMost(it.totalMs) }
            .coerceAtMost(run.totalMs)
        val savedRun = run.copy(
            processedMs = processedMs,
            failure = null,
            updatedAtEpochMs = now,
        )
        dao.saveTranscriptionRun(savedRun)
        if (segmentComplete) dao.markTranscribed(segmentId.value)
        savedRun
    }

    override suspend fun recordWindowFailure(
        segmentId: SegmentId,
        checkpoint: TranscriptionCheckpointEntity,
        run: TranscriptionRunEntity,
        failure: TranscriptionFailure,
    ) = database.withTransaction {
        val now = clock.nowEpochMs()
        dao.saveCheckpoint(
            checkpoint.copy(
                state = TranscriptionRunState.FAILED.name,
                failure = failure.name,
                updatedAtEpochMs = now,
            ),
        )
        dao.saveTranscriptionRun(
            run.copy(
                state = TranscriptionRunState.FAILED.name,
                currentSegmentId = segmentId.value,
                failure = failure.name,
                updatedAtEpochMs = now,
            ),
        )
        dao.markTranscriptionFailed(segmentId.value, failure.name)
    }

    override suspend fun transcript(id: SessionId) =
        dao.transcript(id.value).map { it.toDomain() }

    override suspend fun saveEvidence(
        id: SessionId,
        claims: List<EvidenceClaim>,
        draft: DiaryDraft,
    ) = database.withTransaction {
        val existingDraft = dao.draft(id.value)
        dao.deleteMachineClaims(id.value)
        dao.insertClaims(
            claims.map {
                EvidenceClaimEntity(
                    it.id,
                    id.value,
                    it.category.name,
                    it.value,
                    it.normalizedValue,
                    it.status.name,
                    it.confidence,
                    it.origin.name,
                    it.evidence.blockId.value,
                    it.evidence.startMs,
                    it.evidence.endMs,
                    it.evidence.excerpt,
                    it.active,
                    runId = it.runId,
                    packetId = it.packetId,
                    providerClaimKey = it.providerClaimKey,
                    declaredConfidence = it.declaredConfidence,
                    effectiveConfidence = it.effectiveConfidence,
                    claimOrdinal = it.claimOrdinal,
                )
            },
        )
        val fields = existingDraft?.takeIf { it.userEdited }
        dao.saveDraft(
            DiaryDraftEntity(
                id.value,
                id.value,
                draft.mode.name,
                fields?.topics ?: draft.topics,
                fields?.activities ?: draft.activities,
                fields?.pages ?: draft.pages,
                fields?.exercises ?: draft.exercises,
                fields?.homework ?: draft.homework,
                clock.nowEpochMs(),
                fields?.userEdited ?: false,
            ),
        )
    }

    override suspend fun updateSession(id: SessionId, state: SessionState) {
        val current = requireNotNull(dao.session(id.value))
        dao.updateSession(current.copy(state = state.name, updatedAtEpochMs = clock.nowEpochMs()))
    }

    fun observeLatestDraft(): Flow<DiaryDraftEntity?> = dao.observeLatestDraft()

    fun observeLatestClaims(): Flow<List<EvidenceClaim>> =
        dao.observeLatestClaims().map { rows -> rows.map { it.toDomain() } }

    fun observeClaims(sessionId: String): Flow<List<EvidenceClaim>> =
        dao.observeClaims(sessionId).map { rows -> rows.map { it.toDomain() } }

    fun observeRun(sessionId: String): Flow<TranscriptionRunEntity?> =
        dao.observeTranscriptionRun(sessionId)

    override suspend fun draft(id: SessionId) = dao.draft(id.value)

    suspend fun saveEditedDraft(draft: DiaryDraftEntity) =
        dao.saveDraft(draft.copy(updatedAtEpochMs = clock.nowEpochMs(), userEdited = true))

    private fun EvidenceClaimEntity.toDomain() =
        EvidenceClaim(
            id,
            ClaimCategory.valueOf(category),
            value,
            normalizedValue,
            ClaimStatus.valueOf(status),
            confidence,
            ClaimOrigin.valueOf(origin),
            EvidenceRef(BlockId(blockId), startMs, endMs, excerpt),
            active,
            runId = runId,
            packetId = packetId,
            providerClaimKey = providerClaimKey.ifEmpty { id },
            declaredConfidence = declaredConfidence,
            effectiveConfidence = effectiveConfidence,
            claimOrdinal = claimOrdinal,
        )

    private fun TranscriptSpanEntity.toDomain() =
        TranscriptSpan(id, audioSegmentId, BlockId(blockId), startMs, endMs, text, confidence)

    private fun TranscriptSpan.toEntity() =
        TranscriptSpanEntity(id, audioSegmentId, blockId.value, startMs, endMs, text, confidence)
}

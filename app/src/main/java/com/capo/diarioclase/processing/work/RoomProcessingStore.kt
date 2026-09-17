package com.capo.diarioclase.processing.work

import androidx.room.withTransaction
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.AudioSegmentEntity
import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.ClaimEvidenceEntity
import com.capo.diarioclase.data.db.ClaimSupersessionEntity
import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.DraftFieldRevision
import com.capo.diarioclase.data.db.DraftFieldRevisionEntity
import com.capo.diarioclase.data.db.ReviewAction
import com.capo.diarioclase.processing.semantic.DiaryField
import java.util.UUID
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

    override suspend fun persistedClaims(sessionId: SessionId): List<EvidenceClaim> = database.withTransaction {
        val rows = dao.claimsSnapshot(sessionId.value)
        val claims = ArrayList<EvidenceClaim>(rows.size)
        for (row in rows) {
            val evidenceRows = dao.claimEvidence(row.id)
            val spans = if (evidenceRows.isNotEmpty()) {
                dao.spansByIds(evidenceRows.map { it.transcriptSpanId }).associateBy { it.id }
            } else {
                emptyMap()
            }
            // Rehidrata la evidencia completa desde los spans de transcripción reales; mientras
            // la sesión no se aprobó, siguen disponibles y por eso reabrir conserva las evidencias.
            val evidences = evidenceRows.mapNotNull { evidence ->
                spans[evidence.transcriptSpanId]?.let { span ->
                    EvidenceRef(
                        blockId = BlockId(span.blockId),
                        startMs = span.startMs,
                        endMs = span.endMs,
                        excerpt = span.text,
                        contextual = evidence.contextual,
                    )
                }
            }
            val base = row.toDomain()
            claims += base.copy(
                evidences = evidences.ifEmpty { listOf(base.evidence) },
                transcriptSpanIds = evidenceRows.map { it.transcriptSpanId },
                supersedesClaimKeys = dao.claimSupersessions(row.id),
            )
        }
        claims
    }

    fun observeInterpretationRun(sessionId: String): Flow<com.capo.diarioclase.data.db.InterpretationRunEntity?> =
        dao.observeLatestRun(sessionId)

    fun observeLatestInterpretationRun(): Flow<com.capo.diarioclase.data.db.InterpretationRunEntity?> =
        dao.observeLatestRunAny()

    /** Último intento de proveedor de la corrida más reciente (Fase 6, Q5), para la UI de progreso. */
    fun observeLatestAttempt(): Flow<com.capo.diarioclase.data.db.ProviderAttemptEntity?> =
        dao.observeLatestAttempt()

    /**
     * Guarda la ficha reproyectada respetando una edición previa del docente: si la ficha
     * almacenada está marcada como editada, sus campos ganan; siempre se actualiza el modo.
     * No reescribe claims: la reproyección de Task I7 parte de los ya persistidos.
     */
    override suspend fun mergeFieldEditsAndSave(draft: DiaryDraft): DiaryDraft = database.withTransaction {
        val existing = dao.draft(draft.sessionId)
        val merged = draft.protectedBy(existing, mode = draft.mode.name)
        dao.saveDraft(merged)
        draft.copy(
            topics = merged.topics,
            activities = merged.activities,
            pages = merged.pages,
            exercises = merged.exercises,
            homework = merged.homework,
        )
    }

    /**
     * Combina una ficha nueva con las ediciones previas del docente, protegiendo **solo** los
     * campos marcados como editados (máscara por campo de Q4). Conserva la máscara y la bandera
     * global legacy.
     */
    private fun DiaryDraft.protectedBy(existing: DiaryDraftEntity?, mode: String): DiaryDraftEntity =
        DiaryDraftEntity(
            id = sessionId,
            sessionId = sessionId,
            mode = mode,
            topics = if (existing?.editedTopics == true) existing.topics else topics,
            activities = if (existing?.editedActivities == true) existing.activities else activities,
            pages = if (existing?.editedPages == true) existing.pages else pages,
            exercises = if (existing?.editedExercises == true) existing.exercises else exercises,
            homework = if (existing?.editedHomework == true) existing.homework else homework,
            updatedAtEpochMs = clock.nowEpochMs(),
            userEdited = existing?.userEdited ?: false,
            editedTopics = existing?.editedTopics ?: false,
            editedActivities = existing?.editedActivities ?: false,
            editedPages = existing?.editedPages ?: false,
            editedExercises = existing?.editedExercises ?: false,
            editedHomework = existing?.editedHomework ?: false,
            // Un resumen nuevo (interpretación remota) reemplaza; una reproyección local de modo
            // llega con summary vacío y conserva el resumen ya persistido.
            summary = summary.ifBlank { existing?.summary.orEmpty() },
        )

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
        dao.deleteClaimEvidenceForSession(id.value)
        dao.deleteClaimSupersessionsForSession(id.value)
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
                    reason = it.reason,
                )
            },
        )
        // Persiste el grafo normalizado: evidencia resuelta a spans reales y supersesiones.
        // Así reabrir la sesión conserva la evidencia múltiple y las correcciones.
        claims.forEach { claim ->
            if (claim.transcriptSpanIds.isNotEmpty()) {
                dao.insertClaimEvidence(
                    claim.transcriptSpanIds.mapIndexed { index, spanId ->
                        ClaimEvidenceEntity(
                            claimId = claim.id,
                            transcriptSpanId = spanId,
                            ordinal = index,
                            contextual = claim.evidences.getOrNull(index)?.contextual ?: false,
                        )
                    },
                )
            }
            if (claim.supersedesClaimKeys.isNotEmpty()) {
                dao.insertClaimSupersessions(
                    claim.supersedesClaimKeys.map { old -> ClaimSupersessionEntity(claim.id, old) },
                )
            }
        }
        dao.saveDraft(draft.protectedBy(existingDraft, mode = draft.mode.name))
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

    /**
     * Carga los marcadores manuales de la sesión como señales locales (Fase 6, Q7). Los ids de
     * marcador y de bloque quedan locales y nunca viajan a un proveedor.
     */
    override suspend fun loadSignals(id: SessionId): com.capo.diarioclase.processing.semantic.LocalInterpretationSignals =
        com.capo.diarioclase.processing.semantic.LocalInterpretationSignals(
            markers = dao.markersForSession(id.value).map { marker ->
                com.capo.diarioclase.processing.semantic.ManualMarkerSignal(
                    markerId = marker.id,
                    type = marker.type,
                    blockId = marker.blockId,
                    offsetMs = marker.offsetMs,
                )
            },
        )

    suspend fun saveEditedDraft(draft: DiaryDraftEntity) =
        dao.saveDraft(draft.copy(updatedAtEpochMs = clock.nowEpochMs(), userEdited = true))

    // --- Revisión estructurada del docente (Fase 6, Q4) --------------------------------

    /**
     * Edita un campo de la ficha protegiendo solo ese campo (máscara por campo). Registra la
     * revisión antes de actualizar la ficha. No toca proveedores ni el scheduler.
     */
    suspend fun saveFieldEdit(session: SessionId, field: DiaryField, value: String): Unit =
        database.withTransaction {
            val existing = dao.draft(session.value) ?: return@withTransaction
            dao.insertRevision(
                revision(session.value, field.name, existing.fieldValue(field), value, ReviewAction.CORRECT, claimId = null),
            )
            dao.saveDraft(
                existing.withField(field, value)
                    .copy(updatedAtEpochMs = clock.nowEpochMs(), userEdited = true),
            )
        }

    suspend fun isFieldEdited(session: SessionId, field: DiaryField): Boolean =
        dao.draft(session.value)?.isEdited(field) ?: false

    /**
     * Aplica una decisión de revisión sobre un claim (aceptar/rechazar/corregir). Escribe la
     * revisión antes de actualizar el claim. Corregir exige un valor no vacío. Sin red ni scheduler.
     */
    suspend fun reviewClaim(claimId: String, action: ReviewAction, correctedValue: String?): Unit =
        database.withTransaction {
            if (action == ReviewAction.CORRECT) require(!correctedValue.isNullOrBlank())
            val claim = requireNotNull(dao.claimById(claimId)) { "No existe el elemento por revisar." }
            val before = claim.value
            val after = when (action) {
                ReviewAction.CORRECT -> correctedValue.orEmpty()
                ReviewAction.REJECT -> ""
                ReviewAction.ACCEPT -> before
            }
            val field = categoryField(claim.category).name
            dao.insertRevision(revision(claim.sessionId, field, before, after, action, claimId))
            when (action) {
                ReviewAction.ACCEPT -> dao.acceptClaim(claimId)
                ReviewAction.REJECT -> dao.setClaimActive(claimId, false)
                ReviewAction.CORRECT -> dao.correctClaim(claimId, correctedValue!!, correctedValue)
            }
            claim.sessionId
        }.let { sessionId ->
            val mode = dao.draft(sessionId)?.mode
                ?.let { runCatching { com.capo.diarioclase.processing.evidence.InterpretationMode.valueOf(it) }.getOrNull() }
                ?: com.capo.diarioclase.processing.evidence.InterpretationMode.CONSERVATIVE
            LocalDraftReprojector(this).reproject(SessionId(sessionId), mode)
        }

    suspend fun revisions(claimId: String): List<DraftFieldRevision> =
        dao.revisionsForClaim(claimId).map { it.toDomain() }

    suspend fun sessionRevisions(sessionId: String): List<DraftFieldRevision> =
        dao.revisionsForSession(sessionId).map { it.toDomain() }

    private fun revision(
        sessionId: String,
        field: String,
        before: String,
        after: String,
        action: ReviewAction,
        claimId: String?,
    ) = DraftFieldRevisionEntity(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        field = field,
        beforeValue = before,
        afterValue = after,
        actor = "USER",
        action = action.name,
        claimId = claimId,
        createdAtEpochMs = clock.nowEpochMs(),
    )

    private fun DraftFieldRevisionEntity.toDomain() = DraftFieldRevision(
        id, sessionId, field, beforeValue, afterValue, actor, ReviewAction.valueOf(action), claimId, createdAtEpochMs,
    )

    private fun categoryField(category: String): DiaryField = when (category) {
        ClaimCategory.TOPIC.name -> DiaryField.TOPICS
        ClaimCategory.ACTIVITY.name -> DiaryField.ACTIVITIES
        ClaimCategory.PAGE.name -> DiaryField.PAGES
        ClaimCategory.EXERCISE.name -> DiaryField.EXERCISES
        else -> DiaryField.HOMEWORK
    }

    private fun DiaryDraftEntity.fieldValue(field: DiaryField): String = when (field) {
        DiaryField.TOPICS -> topics
        DiaryField.ACTIVITIES -> activities
        DiaryField.PAGES -> pages
        DiaryField.EXERCISES -> exercises
        DiaryField.HOMEWORK -> homework
    }

    private fun DiaryDraftEntity.isEdited(field: DiaryField): Boolean = when (field) {
        DiaryField.TOPICS -> editedTopics
        DiaryField.ACTIVITIES -> editedActivities
        DiaryField.PAGES -> editedPages
        DiaryField.EXERCISES -> editedExercises
        DiaryField.HOMEWORK -> editedHomework
    }

    private fun DiaryDraftEntity.withField(field: DiaryField, value: String): DiaryDraftEntity = when (field) {
        DiaryField.TOPICS -> copy(topics = value, editedTopics = true)
        DiaryField.ACTIVITIES -> copy(activities = value, editedActivities = true)
        DiaryField.PAGES -> copy(pages = value, editedPages = true)
        DiaryField.EXERCISES -> copy(exercises = value, editedExercises = true)
        DiaryField.HOMEWORK -> copy(homework = value, editedHomework = true)
    }

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
            reason = reason,
        )

    private fun TranscriptSpanEntity.toDomain() =
        TranscriptSpan(id, audioSegmentId, BlockId(blockId), startMs, endMs, text, confidence)

    private fun TranscriptSpan.toEntity() =
        TranscriptSpanEntity(id, audioSegmentId, blockId.value, startMs, endMs, text, confidence)
}

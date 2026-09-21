package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.EditorialReportEntity
import kotlinx.coroutines.flow.Flow

interface EditorialReportStore {
    suspend fun readyFor(sessionId: String, inputHash: String): EditorialReportEntity?
    suspend fun begin(sessionId: String, inputHash: String)
    suspend fun saveReadyIfCurrent(
        sessionId: String,
        inputHash: String,
        ready: EditorialReportEntity,
    ): Boolean
    suspend fun markFailedIfCurrent(sessionId: String, inputHash: String, failure: String)
    suspend fun markStale(sessionId: String)
    fun observe(sessionId: String): Flow<EditorialReportEntity?>
    fun observeLatest(): Flow<EditorialReportEntity?>
}

class RoomEditorialReportStore(
    database: DiarioDatabase,
    private val clock: Clock,
) : EditorialReportStore {
    private val dao = database.sessions()

    override suspend fun readyFor(sessionId: String, inputHash: String): EditorialReportEntity? =
        dao.readyEditorialReport(sessionId, inputHash)

    override suspend fun begin(sessionId: String, inputHash: String) {
        dao.saveEditorialReport(
            EditorialReportEntity(
                sessionId = sessionId,
                inputHash = inputHash,
                state = EditorialReportState.GENERATING.name,
                rawJson = "",
                summary = "",
                materialText = "",
                homeworkText = "",
                provider = null,
                modelId = null,
                promptVersion = EditorialReportRequest.PROMPT_VERSION,
                schemaVersion = EditorialReportRequest.SCHEMA_VERSION,
                validatorVersion = EditorialReportValidator.VERSION,
                failure = null,
                updatedAtEpochMs = clock.nowEpochMs(),
            ),
        )
    }

    override suspend fun saveReadyIfCurrent(
        sessionId: String,
        inputHash: String,
        ready: EditorialReportEntity,
    ): Boolean = dao.saveEditorialReadyIfCurrent(
        sessionId = sessionId,
        inputHash = inputHash,
        rawJson = ready.rawJson,
        summary = ready.summary,
        materialText = ready.materialText,
        homeworkText = ready.homeworkText,
        provider = ready.provider,
        modelId = ready.modelId,
        promptVersion = ready.promptVersion,
        schemaVersion = ready.schemaVersion,
        validatorVersion = ready.validatorVersion,
        updatedAtEpochMs = clock.nowEpochMs(),
    ) == 1

    override suspend fun markFailedIfCurrent(sessionId: String, inputHash: String, failure: String) {
        dao.markEditorialFailedIfCurrent(sessionId, inputHash, failure, clock.nowEpochMs())
    }

    override suspend fun markStale(sessionId: String) {
        dao.markEditorialReportStale(sessionId, clock.nowEpochMs())
    }

    override fun observe(sessionId: String): Flow<EditorialReportEntity?> =
        dao.observeEditorialReport(sessionId)

    override fun observeLatest(): Flow<EditorialReportEntity?> =
        dao.observeLatestEditorialReport()
}

package com.capo.diarioclase.diary

import com.capo.diarioclase.data.db.CerLevel
import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.InterpretationMode
import kotlinx.coroutines.flow.Flow

data class DiaryEntry(
    val id: String,
    val sessionId: SessionId,
    val pedagogicalDate: String,
    val level: CerLevel?,
    val topics: String,
    val activities: String,
    val pages: String,
    val completedExercises: String,
    val homework: String,
    val approvedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val temporariesDeleted: Boolean,
    val reportSummary: String = "",
    val reportMaterial: String = "",
    val reportHomework: String = "",
    val reportAuditJson: String = "",
    val reportProvider: String? = null,
    val reportModelId: String? = null,
    val reportInputHash: String = "",
    val reportPromptVersion: String = "",
    val reportSchemaVersion: String = "",
    val reportValidatorVersion: String = "",
)

sealed interface DiarySaveResult {
    data class Verified(val entry: DiaryEntry) : DiarySaveResult
    data class Failed(val reason: String) : DiarySaveResult
}

interface DiaryRepository {
    suspend fun saveVerified(sessionId: SessionId, draft: DiaryDraftEntity): DiarySaveResult
    suspend fun getBySession(sessionId: SessionId): DiaryEntry?
    fun observeEntries(query: String): Flow<List<DiaryEntry>>
    suspend fun update(entry: DiaryEntry)
    /** Cleanup-only field update. Never inserts or replaces a permanent diary. */
    suspend fun markTemporariesDeleted(sessionId: SessionId, diaryId: String): Boolean
    suspend fun delete(diaryId: String)
    fun observeMode(): Flow<InterpretationMode>
    suspend fun setMode(mode: InterpretationMode)
}

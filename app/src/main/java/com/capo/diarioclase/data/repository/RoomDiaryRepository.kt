package com.capo.diarioclase.data.repository

import androidx.room.withTransaction
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.AppSettingEntity
import com.capo.diarioclase.data.db.CerLevel
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.DiaryEntryEntity
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.diary.DiaryRepository
import com.capo.diarioclase.diary.DiarySaveResult
import com.capo.diarioclase.processing.evidence.InterpretationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

class RoomDiaryRepository(
    private val database: DiarioDatabase,
    private val clock: Clock,
    private val idProvider: IdProvider = IdProvider { UUID.randomUUID().toString() },
) : DiaryRepository {
    private val dao = database.sessions()

    override suspend fun saveVerified(sessionId: SessionId, draft: DiaryDraftEntity): DiarySaveResult = try {
        database.withTransaction {
            if (draft.sessionId != sessionId.value) return@withTransaction DiarySaveResult.Failed("El borrador no corresponde a la sesión")
            val session = dao.session(sessionId.value)
                ?: return@withTransaction DiarySaveResult.Failed("No existe la sesión")
            val existing = dao.diaryBySession(sessionId.value)
            val entry = DiaryEntryEntity(
                id = existing?.id ?: idProvider.next(),
                sessionId = sessionId.value,
                pedagogicalDate = session.pedagogicalDate,
                level = session.level,
                topics = draft.topics,
                activities = draft.activities,
                pages = draft.pages,
                completedExercises = draft.exercises,
                homework = draft.homework,
                approvedAtEpochMs = existing?.approvedAtEpochMs ?: clock.nowEpochMs(),
                updatedAtEpochMs = clock.nowEpochMs(),
                temporariesDeleted = existing?.temporariesDeleted ?: false,
            )
            dao.saveDiary(entry)
            dao.diaryBySession(sessionId.value)
                ?.takeIf { it == entry }
                ?.let { DiarySaveResult.Verified(it.toDomain()) }
                ?: DiarySaveResult.Failed("La ficha guardada no coincide con la verificación")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        DiarySaveResult.Failed("No se pudo guardar la ficha permanente")
    }

    override suspend fun getBySession(sessionId: SessionId): DiaryEntry? = dao.diaryBySession(sessionId.value)?.toDomain()

    override fun observeEntries(query: String): Flow<List<DiaryEntry>> {
        val normalizedQuery = normalize(query)
        return dao.observeDiaries().map { entries ->
            entries.map { it.toDomain() }.filter { entry ->
                normalizedQuery.isEmpty() || entry.searchableText().contains(normalizedQuery)
            }
        }
    }

    override suspend fun update(entry: DiaryEntry) {
        database.withTransaction {
            val current = dao.diaryById(entry.id)
            check(current != null && current.sessionId == entry.sessionId.value) { "Este diario ya no está disponible" }
            // Only user-editable fields may cross this boundary. Reread and write in one transaction
            // so a cleanup commit cannot be undone by a stale archive editor.
            dao.saveDiary(current.copy(
                pedagogicalDate = entry.pedagogicalDate,
                level = entry.level?.name,
                topics = entry.topics,
                activities = entry.activities,
                pages = entry.pages,
                completedExercises = entry.completedExercises,
                homework = entry.homework,
                updatedAtEpochMs = clock.nowEpochMs(),
            ))
        }
    }

    override suspend fun markTemporariesDeleted(sessionId: SessionId, diaryId: String): Boolean =
        dao.markDiaryTemporariesDeleted(sessionId.value, diaryId) == 1

    override suspend fun delete(diaryId: String) {
        database.withTransaction {
            val current = dao.diaryById(diaryId) ?: return@withTransaction
            // Include the final session state: between marking the flag and archiving, process
            // recovery still needs this row. The read and delete share the archive edit transaction.
            check(current.temporariesDeleted && dao.session(current.sessionId)?.state == "ARCHIVED") {
                "Primero completá la limpieza temporal de este diario. La ficha sigue guardada"
            }
            dao.deleteDiary(diaryId)
        }
    }

    override fun observeMode(): Flow<InterpretationMode> = dao.observeInterpretationMode().map { stored ->
        runCatching { InterpretationMode.valueOf(stored) }.getOrDefault(InterpretationMode.CONSERVATIVE)
    }

    override suspend fun setMode(mode: InterpretationMode) {
        dao.saveSetting(AppSettingEntity("interpretation_mode", mode.name))
    }

    private fun DiaryEntryEntity.toDomain() = DiaryEntry(
        id = id,
        sessionId = SessionId(sessionId),
        pedagogicalDate = pedagogicalDate,
        level = level?.let(CerLevel::valueOf),
        topics = topics,
        activities = activities,
        pages = pages,
        completedExercises = completedExercises,
        homework = homework,
        approvedAtEpochMs = approvedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        temporariesDeleted = temporariesDeleted,
    )

    private fun DiaryEntry.searchableText() = listOf(
        pedagogicalDate,
        level?.name.orEmpty(),
        topics,
        activities,
        pages,
        completedExercises,
        homework,
    ).joinToString(" ").let(::normalize)

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
}

package com.capo.diarioclase.diary.cleanup

import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.SegmentId
import com.capo.diarioclase.data.db.SessionDao
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.SessionState
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.diary.DiaryRepository
import com.capo.diarioclase.diary.DiarySaveResult
import com.capo.diarioclase.recording.audio.CleanupFileStore
import com.capo.diarioclase.recording.audio.DeleteResult
import java.util.concurrent.CancellationException

sealed interface CleanupOutcome {
    data class Archived(val diaryId: String) : CleanupOutcome
    data class Pending(val diaryId: String, val remainingFiles: Int) : CleanupOutcome
    data class SaveFailed(val reason: String) : CleanupOutcome
}

/**
 * Room operations required to remove the temporary side of a diary session.
 *
 * Keeping this narrow makes the cleanup order explicit and prevents any other
 * caller from treating a database row as proof that its audio has been removed.
 */
interface TemporaryCleanupStore {
    suspend fun updateSessionState(sessionId: SessionId, state: SessionState, nowEpochMs: Long)
    suspend fun segmentsForCleanup(sessionId: SessionId): List<SegmentId>
    suspend fun markSegmentsDeleted(ids: List<SegmentId>)
    suspend fun deleteSessionTemporaryRows(sessionId: SessionId)
    suspend fun temporaryRowCount(sessionId: SessionId): Int
}

class RoomTemporaryCleanupStore(private val dao: SessionDao) : TemporaryCleanupStore {
    override suspend fun updateSessionState(sessionId: SessionId, state: SessionState, nowEpochMs: Long) {
        dao.updateSessionStateUnchecked(sessionId.value, state.name, nowEpochMs)
    }

    override suspend fun segmentsForCleanup(sessionId: SessionId) =
        dao.segmentsForCleanup(sessionId.value).map { SegmentId(it.id) }

    override suspend fun markSegmentsDeleted(ids: List<SegmentId>) {
        if (ids.isNotEmpty()) dao.markSegmentsDeleted(ids.map(SegmentId::value))
    }

    override suspend fun deleteSessionTemporaryRows(sessionId: SessionId) {
        dao.deleteTranscriptsForSession(sessionId.value)
        dao.deleteEvidenceForSession(sessionId.value)
        dao.deleteDraftForSession(sessionId.value)
        dao.deleteCheckpoints(sessionId.value)
        dao.deleteTranscriptionRun(sessionId.value)
        dao.deleteInterpretationCacheForSession(sessionId.value)
        dao.deleteEditorialReport(sessionId.value)
    }

    override suspend fun temporaryRowCount(sessionId: SessionId) = dao.temporaryRowCount(sessionId.value)
}

/** The sole approval path that is allowed to delete temporary recording data. */
class CleanupCoordinator(
    private val diaries: DiaryRepository,
    private val files: CleanupFileStore,
    private val temporaryStore: TemporaryCleanupStore,
    private val clock: Clock,
) {
    suspend fun approveAndClean(sessionId: SessionId, draft: com.capo.diarioclase.data.db.DiaryDraftEntity): CleanupOutcome {
        val saved = try {
            diaries.saveVerified(sessionId, draft)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CleanupOutcome.SaveFailed("No se pudo guardar la ficha permanente")
        }
        val verified = when (saved) {
            is DiarySaveResult.Verified -> saved.entry
            is DiarySaveResult.Failed -> return CleanupOutcome.SaveFailed(saved.reason)
        }
        val reread = try {
            diaries.getBySession(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CleanupOutcome.SaveFailed("No se pudo verificar la ficha permanente")
        }
        if (reread != verified) {
            return CleanupOutcome.SaveFailed("La ficha guardada no coincide con la verificación")
        }

        return try {
            temporaryStore.updateSessionState(sessionId, SessionState.APPROVED, clock.nowEpochMs())
            cleanVerifiedDiary(sessionId, verified)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            pending(sessionId, verified, 0)
        }
    }

    /** Retries only temporary cleanup for an already verified permanent diary. */
    suspend fun retryCleanup(sessionId: SessionId): CleanupOutcome {
        val verified = try {
            diaries.getBySession(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CleanupOutcome.SaveFailed("No se pudo recuperar la ficha permanente")
        } ?: return CleanupOutcome.SaveFailed("No existe una ficha permanente para limpiar")

        return cleanVerifiedDiary(sessionId, verified)
    }

    private suspend fun cleanVerifiedDiary(sessionId: SessionId, diary: DiaryEntry): CleanupOutcome {
        val segments = try {
            temporaryStore.segmentsForCleanup(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return pending(sessionId, diary, 0)
        }
        var fileDeletionFailed = false
        val absentAfterAttempt = mutableListOf<SegmentId>()

        try {
            for (segmentId in segments) {
                if (files.exists(segmentId)) {
                    if (files.delete(segmentId) is DeleteResult.Failed) fileDeletionFailed = true
                }
                if (!files.exists(segmentId)) absentAfterAttempt += segmentId
            }
            temporaryStore.markSegmentsDeleted(absentAfterAttempt)

            val remainingFiles = remainingFileCount(segments)
            if (remainingFiles == 0 && !fileDeletionFailed) {
                temporaryStore.deleteSessionTemporaryRows(sessionId)
            }
            val remainingRows = temporaryStore.temporaryRowCount(sessionId)
            if (remainingFiles == 0 && remainingRows == 0 && !fileDeletionFailed) {
                if (!diaries.markTemporariesDeleted(sessionId, diary.id)) {
                    return CleanupOutcome.SaveFailed("La ficha permanente ya no está disponible")
                }
                temporaryStore.updateSessionState(sessionId, SessionState.ARCHIVED, clock.nowEpochMs())
                return CleanupOutcome.Archived(diary.id)
            }
            return pending(sessionId, diary, remainingFiles)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return pending(sessionId, diary, remainingFileCount(segments))
        }
    }

    private suspend fun pending(sessionId: SessionId, diary: DiaryEntry, remainingFiles: Int): CleanupOutcome {
        try {
            temporaryStore.updateSessionState(sessionId, SessionState.CLEANUP_PENDING, clock.nowEpochMs())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The permanent diary is already verified; retain a recoverable result even if its pending flag cannot persist yet.
        }
        return CleanupOutcome.Pending(diary.id, remainingFiles)
    }

    private suspend fun remainingFileCount(segments: List<SegmentId>): Int {
        var count = 0
        for (segmentId in segments) {
            val exists = try {
                files.exists(segmentId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                true
            }
            if (exists) count += 1
        }
        return count
    }
}

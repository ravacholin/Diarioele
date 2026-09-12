package com.capo.diarioclase.diary.cleanup

import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.SegmentId
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.SessionState
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.diary.DiaryRepository
import com.capo.diarioclase.diary.DiarySaveResult
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.recording.audio.DeleteResult
import com.capo.diarioclase.recording.audio.CleanupFileStore
import com.capo.diarioclase.recording.audio.OpenSegment
import com.capo.diarioclase.recording.audio.ReadySegment
import com.capo.diarioclase.recording.audio.SegmentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupCoordinatorTest {
    @Test fun `diary is verified before any deletion`() = runTest {
        val events = mutableListOf<String>()
        val diaries = MemoryDiaries(events)
        val cleanup = MemoryCleanup(events, listOf(SegmentId("a")))
        val files = MemoryFiles(events)
        val coordinator = CleanupCoordinator(diaries, files, cleanup, Clock { 1_000 })

        val result = coordinator.approveAndClean(SessionId("s"), draft())

        assertEquals(listOf("save", "read-back", "approved", "delete-audio", "delete-rows", "verify-empty", "archived"), events)
        assertEquals(CleanupOutcome.Archived("diary-s"), result)
        assertTrue(diaries.entry?.temporariesDeleted == true)
    }

    @Test fun `save mismatch deletes nothing`() = runTest {
        val events = mutableListOf<String>()
        val diaries = MemoryDiaries(events, rereadMatches = false)
        val cleanup = MemoryCleanup(events, listOf(SegmentId("a")))
        val files = MemoryFiles(events)
        val coordinator = CleanupCoordinator(diaries, files, cleanup, Clock { 1_000 })

        val result = coordinator.approveAndClean(SessionId("s"), draft())

        assertTrue(result is CleanupOutcome.SaveFailed)
        assertEquals(listOf("save", "read-back"), events)
        assertEquals(null, cleanup.state)
        assertFalse(files.deleted.isNotEmpty())
    }

    @Test fun `failed file deletion preserves diary and becomes pending`() = runTest {
        val events = mutableListOf<String>()
        val diaries = MemoryDiaries(events)
        val cleanup = MemoryCleanup(events, listOf(SegmentId("a"), SegmentId("b")))
        val files = MemoryFiles(events, failures = setOf(SegmentId("b")))
        val coordinator = CleanupCoordinator(diaries, files, cleanup, Clock { 1_000 })

        val result = coordinator.approveAndClean(SessionId("s"), draft())

        assertEquals(CleanupOutcome.Pending("diary-s", remainingFiles = 1), result)
        assertNotNull(diaries.entry)
        assertEquals(SessionState.CLEANUP_PENDING, cleanup.state)
        assertEquals(listOf(SegmentId("a")), cleanup.deletedSegmentRows)
        assertFalse(cleanup.deletedSessionRows)
    }

    @Test fun `post verification state failure becomes pending`() = runTest {
        val events = mutableListOf<String>()
        val diaries = MemoryDiaries(events)
        val cleanup = MemoryCleanup(events, listOf(SegmentId("a")), failApprovedState = true)
        val files = MemoryFiles(events)
        val coordinator = CleanupCoordinator(diaries, files, cleanup, Clock { 1_000 })

        val result = coordinator.approveAndClean(SessionId("s"), draft())

        assertEquals(CleanupOutcome.Pending("diary-s", remainingFiles = 0), result)
        assertNotNull(diaries.entry)
        assertEquals(SessionState.CLEANUP_PENDING, cleanup.state)
        assertTrue(files.deleted.isEmpty())
    }

    @Test fun `retry uses verified diary and missing files without resaving`() = runTest {
        val events = mutableListOf<String>()
        val diaries = MemoryDiaries(events)
        diaries.entry = existingEntry()
        val cleanup = MemoryCleanup(events, listOf(SegmentId("a")))
        val files = MemoryFiles(events, initiallyMissing = setOf(SegmentId("a")))
        val coordinator = CleanupCoordinator(diaries, files, cleanup, Clock { 1_000 })

        val result = coordinator.retryCleanup(SessionId("s"))

        assertEquals(CleanupOutcome.Archived("diary-s"), result)
        assertEquals(0, diaries.saveCalls)
        assertEquals("diary-s", diaries.entry?.id)
        assertEquals(emptyList<SegmentId>(), files.deleted)
        assertEquals(listOf(SegmentId("a")), cleanup.deletedSegmentRows)
    }

    @Test fun `unreadable file storage becomes pending without changing segment rows`() = runTest {
        val events = mutableListOf<String>()
        val diaries = MemoryDiaries(events)
        val cleanup = MemoryCleanup(events, listOf(SegmentId("a")))
        val files = MemoryFiles(events, existsFailure = true)
        val coordinator = CleanupCoordinator(diaries, files, cleanup, Clock { 1_000 })

        val result = coordinator.approveAndClean(SessionId("s"), draft())

        assertEquals(CleanupOutcome.Pending("diary-s", remainingFiles = 1), result)
        assertEquals(SessionState.CLEANUP_PENDING, cleanup.state)
        assertEquals(emptyList<SegmentId>(), cleanup.deletedSegmentRows)
        assertFalse(cleanup.deletedSessionRows)
    }

    @Test fun `segment metadata changes only after physical absence is verified`() = runTest {
        val events = mutableListOf<String>()
        val diaries = MemoryDiaries(events)
        val cleanup = MemoryCleanup(events, listOf(SegmentId("a")), recordSegmentMark = true)
        val files = MemoryFiles(events, recordExistence = true)
        val coordinator = CleanupCoordinator(diaries, files, cleanup, Clock { 1_000 })

        coordinator.approveAndClean(SessionId("s"), draft())

        assertTrue(events.indexOf("file-absent") < events.indexOf("mark-segment-row"))
    }

    private fun draft() = DiaryDraftEntity(
        id = "draft-s",
        sessionId = "s",
        mode = InterpretationMode.CONSERVATIVE.name,
        topics = "Tema",
        activities = "Actividad",
        pages = "10",
        exercises = "1",
        homework = "Tarea",
        updatedAtEpochMs = 500,
    )

    private fun existingEntry() = DiaryEntry(
        id = "diary-s",
        sessionId = SessionId("s"),
        pedagogicalDate = "2026-09-12",
        level = null,
        topics = "Tema",
        activities = "Actividad",
        pages = "10",
        completedExercises = "1",
        homework = "Tarea",
        approvedAtEpochMs = 1_000,
        updatedAtEpochMs = 1_000,
        temporariesDeleted = false,
    )
}

private class MemoryDiaries(
    private val events: MutableList<String>,
    private val rereadMatches: Boolean = true,
) : DiaryRepository {
    var entry: DiaryEntry? = null
    var saveCalls = 0

    override suspend fun saveVerified(sessionId: SessionId, draft: DiaryDraftEntity): DiarySaveResult {
        events += "save"
        saveCalls += 1
        val current = entry ?: DiaryEntry(
            id = "diary-${sessionId.value}",
            sessionId = sessionId,
            pedagogicalDate = "2026-09-12",
            level = null,
            topics = draft.topics,
            activities = draft.activities,
            pages = draft.pages,
            completedExercises = draft.exercises,
            homework = draft.homework,
            approvedAtEpochMs = 1_000,
            updatedAtEpochMs = 1_000,
            temporariesDeleted = false,
        )
        entry = current
        return DiarySaveResult.Verified(current)
    }

    override suspend fun getBySession(sessionId: SessionId): DiaryEntry? {
        events += "read-back"
        return entry?.let { if (rereadMatches) it else it.copy(topics = "Distinto") }
    }

    override fun observeEntries(query: String): Flow<List<DiaryEntry>> = emptyFlow()
    override suspend fun update(entry: DiaryEntry) { this.entry = entry }
    override suspend fun markTemporariesDeleted(sessionId: SessionId, diaryId: String): Boolean {
        val current = entry?.takeIf { it.sessionId == sessionId && it.id == diaryId } ?: return false
        entry = current.copy(temporariesDeleted = true)
        return true
    }
    override suspend fun delete(diaryId: String) = Unit
    override fun observeMode(): Flow<InterpretationMode> = emptyFlow()
    override suspend fun setMode(mode: InterpretationMode) = Unit
}

private class MemoryCleanup(
    private val events: MutableList<String>,
    private val segmentIds: List<SegmentId>,
    private val recordSegmentMark: Boolean = false,
    private val failApprovedState: Boolean = false,
) : TemporaryCleanupStore {
    var state: SessionState? = null
    var deletedSegmentRows = emptyList<SegmentId>()
    var deletedSessionRows = false

    override suspend fun updateSessionState(sessionId: SessionId, state: SessionState, nowEpochMs: Long) {
        if (state == SessionState.APPROVED && failApprovedState) error("Estado temporal no disponible")
        this.state = state
        events += if (state == SessionState.APPROVED) "approved" else "archived"
    }

    override suspend fun segmentsForCleanup(sessionId: SessionId) = segmentIds.filterNot { it in deletedSegmentRows }

    override suspend fun markSegmentsDeleted(ids: List<SegmentId>) {
        if (recordSegmentMark) events += "mark-segment-row"
        deletedSegmentRows = (deletedSegmentRows + ids).distinct()
    }

    override suspend fun deleteSessionTemporaryRows(sessionId: SessionId) {
        deletedSessionRows = true
        events += "delete-rows"
    }

    override suspend fun temporaryRowCount(sessionId: SessionId): Int {
        events += "verify-empty"
        return segmentsForCleanup(sessionId).size + if (deletedSessionRows) 0 else 3
    }
}

private class MemoryFiles(
    private val events: MutableList<String>,
    private val failures: Set<SegmentId> = emptySet(),
    initiallyMissing: Set<SegmentId> = emptySet(),
    private val existsFailure: Boolean = false,
    private val recordExistence: Boolean = false,
) : SegmentStore, CleanupFileStore {
    private val present = mutableSetOf<SegmentId>().apply { addAll(setOf(SegmentId("a"), SegmentId("b")) - initiallyMissing) }
    val deleted = mutableListOf<SegmentId>()

    override suspend fun open(blockId: com.capo.diarioclase.data.db.BlockId, ordinal: Int): OpenSegment = error("unused")
    override suspend fun append(segment: OpenSegment, pcm: ShortArray, count: Int) = Unit
    override suspend fun close(segment: OpenSegment): ReadySegment = error("unused")
    override suspend fun repairOpenSegments(): List<ReadySegment> = emptyList()
    override suspend fun delete(segmentId: SegmentId): DeleteResult {
        events += "delete-audio"
        deleted += segmentId
        return if (segmentId in failures) DeleteResult.Failed("blocked") else DeleteResult.Deleted.also { present.remove(segmentId) }
    }
    override suspend fun exists(segmentId: SegmentId): Boolean {
        if (existsFailure) error("Directorio temporal ilegible")
        val exists = segmentId in present
        if (recordExistence && !exists) events += "file-absent"
        return exists
    }
}

# Safe Diary Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Save one permanent editable diary per session, verify it before deleting temporaries, recover partial cleanup, and expose local archive, search, copy, edit, delete, and interpretation settings.

**Architecture:** Room remains the only source of truth. `RoomDiaryRepository` owns permanent entries and settings; `CleanupCoordinator` is the only component allowed to move a reviewed session through approval and temporary deletion. Compose observes repository flows and delegates all irreversible work through explicit actions.

**Tech Stack:** Kotlin 2.1, Android SDK 35, Jetpack Compose Material 3, Room 2.7, coroutines, JUnit 4, Robolectric

**Spec:** `PHASE3_DESIGN.md`

## Global Constraints

- Android application ID remains `com.capo.diarioclase.phase2`.
- The checked-in development signing key remains unchanged.
- The application does not request `android.permission.INTERNET`.
- Permanent diary fields are topics, activities, pages, completed exercises, and homework.
- No audio, transcript, or evidence row is deleted before permanent save and exact read-back verification.
- Cleanup is idempotent and a partial failure produces `CLEANUP_PENDING`.
- Permanent entries remain editable after audio deletion.
- Clipboard output omits empty fields and all technical metadata.
- Interpretation mode defaults to `CONSERVATIVE` and persists locally.
- UI stays dark, monochrome, rectangular, and contains no emoji.
- Markdown files remain at repository root; no `docs` directory is created.

---

### Task 1: Permanent diary schema and Room migration

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Test: `app/src/test/java/com/capo/diarioclase/data/repository/RoomDiaryRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `SessionEntity`, `DiaryDraftEntity`, and database version 2.
- Produces: `DiaryEntryEntity`, `AppSettingEntity`, DAO persistence and cleanup queries, and `MIGRATION_2_3`.

- [ ] **Step 1: Write failing schema-backed repository tests**

```kotlin
@Test fun `save and reread preserves every visible field`() = runTest {
    val saved = repository.saveVerified(SessionId("s"), sampleDraft())
    assertIs<DiarySaveResult.Verified>(saved)
    assertEquals(sampleDraft().topics, repository.getBySession(SessionId("s"))!!.topics)
}

@Test fun `default interpretation mode is conservative`() = runTest {
    assertEquals(InterpretationMode.CONSERVATIVE, repository.observeMode().first())
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*RoomDiaryRepositoryTest'`

Expected: compilation fails because `RoomDiaryRepository`, `DiaryEntryEntity`, and settings queries do not exist.

- [ ] **Step 3: Add permanent entities**

```kotlin
@Entity(tableName = "diary_entries", indices = [Index(value = ["sessionId"], unique = true)])
data class DiaryEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val pedagogicalDate: String,
    val level: String?,
    val topics: String,
    val activities: String,
    val pages: String,
    val completedExercises: String,
    val homework: String,
    val approvedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val temporariesDeleted: Boolean,
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(@PrimaryKey val key: String, val value: String)
```

- [ ] **Step 4: Add exact DAO operations**

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun saveDiary(entry: DiaryEntryEntity)

@Query("SELECT * FROM diary_entries WHERE sessionId=:sessionId LIMIT 1")
suspend fun diaryBySession(sessionId: String): DiaryEntryEntity?

@Query("SELECT * FROM diary_entries ORDER BY pedagogicalDate DESC, updatedAtEpochMs DESC")
fun observeDiaries(): Flow<List<DiaryEntryEntity>>

@Query("DELETE FROM transcript_spans WHERE audioSegmentId IN (SELECT audio_segments.id FROM audio_segments INNER JOIN blocks ON blocks.id=audio_segments.blockId WHERE blocks.sessionId=:sessionId)")
suspend fun deleteTranscriptsForSession(sessionId: String)

@Query("DELETE FROM evidence_claims WHERE sessionId=:sessionId")
suspend fun deleteEvidenceForSession(sessionId: String)

@Query("DELETE FROM diary_drafts WHERE sessionId=:sessionId")
suspend fun deleteDraftForSession(sessionId: String)
```

- [ ] **Step 5: Add database version 3 and migration**

`MIGRATION_2_3` creates `diary_entries`, its unique `sessionId` index, and `app_settings`. It also adds `userEdited INTEGER NOT NULL DEFAULT 0` to `diary_drafts`; add `val userEdited: Boolean = false` to `DiaryDraftEntity`. Register both new entities in `@Database` and register the migration in `DiarioClaseApp` after `MIGRATION_1_2`.

- [ ] **Step 6: Run focused and full database tests**

Run: `./gradlew testDebugUnitTest --tests '*RoomDiaryRepositoryTest' --tests '*RoomSessionRepositoryTest'`

Expected: PASS with no schema validation exception.

- [ ] **Step 7: Commit**

```bash
git add app/src/main app/src/test
git commit -m "feat: add permanent diary schema"
```

---

### Task 2: Diary repository, search, formatting, and manual override safety

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/diary/DiaryModels.kt`
- Create: `app/src/main/java/com/capo/diarioclase/diary/DiaryClipboardFormatter.kt`
- Create: `app/src/main/java/com/capo/diarioclase/data/repository/RoomDiaryRepository.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/RoomProcessingStore.kt`
- Test: `app/src/test/java/com/capo/diarioclase/diary/DiaryClipboardFormatterTest.kt`
- Test: `app/src/test/java/com/capo/diarioclase/data/repository/RoomDiaryRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 entities and DAO calls.
- Produces: `DiaryEntry`, `DiarySaveResult`, `DiaryRepository`, accent-insensitive search, clipboard formatting, and persisted user edits.

- [ ] **Step 1: Write failing formatter and search tests**

```kotlin
@Test fun `formatter omits empty fields and metadata`() {
    val text = DiaryClipboardFormatter().format(entry(topics="Pasados", pages="", homework="Ejercicio 6"))
    assertEquals("11/09/2026\n\nTemas: Pasados\nTarea: Ejercicio 6", text)
    assertFalse(text.contains("Páginas:"))
    assertFalse(text.contains("confianza", ignoreCase=true))
}

@Test fun `search ignores case and accents`() = runTest {
    repository.saveVerified(draft(topics="Conectores concesivos"))
    assertEquals(1, repository.observeEntries("CONCESÍVOS").first().size)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*DiaryClipboardFormatterTest' --tests '*RoomDiaryRepositoryTest'`

Expected: tests fail because formatter and search are absent.

- [ ] **Step 3: Define stable domain interfaces**

```kotlin
sealed interface DiarySaveResult {
    data class Verified(val entry: DiaryEntry) : DiarySaveResult
    data class Failed(val reason: String) : DiarySaveResult
}

interface DiaryRepository {
    suspend fun saveVerified(sessionId: SessionId, draft: DiaryDraftEntity): DiarySaveResult
    suspend fun getBySession(sessionId: SessionId): DiaryEntry?
    fun observeEntries(query: String): Flow<List<DiaryEntry>>
    suspend fun update(entry: DiaryEntry)
    suspend fun delete(diaryId: String)
    fun observeMode(): Flow<InterpretationMode>
    suspend fun setMode(mode: InterpretationMode)
}
```

- [ ] **Step 4: Implement verified save and normalized search**

`saveVerified` obtains date and level from the session, preserves `approvedAtEpochMs` on idempotent retries, writes the row in a Room transaction, rereads it, and compares all permanent fields. Search normalizes Unicode accents and lowercase in Kotlin, then filters date, level, and all five fields from `observeDiaries()`.

- [ ] **Step 5: Preserve manual draft edits across mode changes**

Use the persisted `DiaryDraftEntity.userEdited` field from Task 1. `saveEditedDraft` sets it to true. `TranscriptionCoordinator` may replace generated fields only when `userEdited` is false; mode changes update `mode` while retaining user-entered field values when it is true.

- [ ] **Step 6: Implement clipboard output**

`DiaryClipboardFormatter.format(entry)` converts ISO date to `dd/MM/yyyy`, adds each nonblank field in the fixed order, and never reads evidence objects.

- [ ] **Step 7: Run all focused tests and commit**

Run: `./gradlew testDebugUnitTest --tests '*DiaryClipboardFormatterTest' --tests '*RoomDiaryRepositoryTest' --tests '*TranscriptionCoordinatorTest'`

Expected: PASS, including a new test named `user edit survives mode change`.

```bash
git add app/src/main app/src/test
git commit -m "feat: add searchable editable diary repository"
```

---

### Task 3: Verified and recoverable temporary cleanup

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/diary/cleanup/CleanupCoordinator.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/recording/audio/SegmentStore.kt`
- Test: `app/src/test/java/com/capo/diarioclase/diary/cleanup/CleanupCoordinatorTest.kt`

**Interfaces:**
- Consumes: `DiaryRepository`, `ProcessingStore`, `SegmentStore`, session state, and Task 1 cleanup queries.
- Produces: `CleanupOutcome` and the only `approveAndClean` path.

- [ ] **Step 1: Write failing event-order and partial-failure tests**

```kotlin
@Test fun `diary is verified before any deletion`() = runTest {
    coordinator.approveAndClean(SessionId("s"), draft())
    assertEquals(listOf("save", "read-back", "approved", "delete-audio", "delete-rows", "verify-empty", "archived"), events)
}

@Test fun `failed file deletion preserves diary and becomes pending`() = runTest {
    files.failFor(SegmentId("b"))
    val result = coordinator.approveAndClean(SessionId("s"), draft())
    assertIs<CleanupOutcome.Pending>(result)
    assertNotNull(diaries.getBySession(SessionId("s")))
    assertEquals(SessionState.CLEANUP_PENDING, sessions.state)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*CleanupCoordinatorTest'`

Expected: compilation fails because `CleanupCoordinator` is absent.

- [ ] **Step 3: Define cleanup outcomes**

```kotlin
sealed interface CleanupOutcome {
    data class Archived(val diaryId: String) : CleanupOutcome
    data class Pending(val diaryId: String, val remainingFiles: Int) : CleanupOutcome
    data class SaveFailed(val reason: String) : CleanupOutcome
}
```

- [ ] **Step 4: Implement strict ordered cleanup**

`approveAndClean` performs: verified save, session `APPROVED`, individual `SegmentStore.delete`, temporary row deletion only after file attempts, file and row recount, then `ARCHIVED` or `CLEANUP_PENDING`. It accepts existing permanent entries and missing files so retries are idempotent.

- [ ] **Step 5: Add verification queries**

Add `temporaryRowCount(sessionId)`, `segmentsForCleanup(sessionId)`, `markSegmentsDeleted(ids)`, and `updateSessionStateUnchecked(sessionId,state,now)` to the DAO. A segment counts as remaining only when its file exists after deletion.

- [ ] **Step 6: Test success, save mismatch, partial deletion, and retry**

Run: `./gradlew testDebugUnitTest --tests '*CleanupCoordinatorTest'`

Expected: PASS for all four paths and exact event order.

- [ ] **Step 7: Commit**

```bash
git add app/src/main app/src/test
git commit -m "feat: verify diary before temporary cleanup"
```

---

### Task 4: Approval and cleanup state in the review flow

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureUiState.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureViewModel.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureScreen.kt`
- Test: `app/src/test/java/com/capo/diarioclase/ui/capture/CaptureViewModelTest.kt`

**Interfaces:**
- Consumes: Task 3 `CleanupCoordinator`.
- Produces: approve confirmation, cleanup progress, pending retry, and transition to archive.

- [ ] **Step 1: Write failing view-model tests**

```kotlin
@Test fun `approval forwards latest edited fields`() = runTest {
    viewModel.onApprove("tema editado", "actividad", "12", "3", "tarea")
    assertEquals("tema editado", actions.approvedDraft!!.topics)
}

@Test fun `pending cleanup remains visible and retryable`() = runTest {
    actions.cleanupResult = CleanupOutcome.Pending("d", 1)
    viewModel.onApprove("t", "a", "p", "e", "h")
    assertEquals(CaptureStatus.CLEANUP_PENDING, viewModel.state.value.status)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*CaptureViewModelTest'`

Expected: compilation fails because approval actions and status are absent.

- [ ] **Step 3: Wire application dependencies and actions**

Create one `RoomDiaryRepository` and one `CleanupCoordinator` in `DiarioClaseApp`. Add `approveAndClean(draft): CleanupOutcome` and `retryCleanup(sessionId): CleanupOutcome` to `CaptureActions`.

- [ ] **Step 4: Add explicit confirmation UI**

The dialog title is `APROBAR Y BORRAR AUDIO`. Its body states that date and five fields remain editable while audio, transcript, and evidence are permanently removed. Only its `APROBAR` button dispatches cleanup.

- [ ] **Step 5: Render recoverable outcomes**

Add `APPROVING`, `CLEANUP_PENDING`, and `ARCHIVED` statuses. Disable duplicate approval while busy. `CLEANUP_PENDING` displays the permanent diary and `REINTENTAR LIMPIEZA`; `ARCHIVED` navigates to the saved entry.

- [ ] **Step 6: Run capture tests and commit**

Run: `./gradlew testDebugUnitTest --tests '*CaptureViewModelTest' --tests '*CleanupCoordinatorTest'`

Expected: PASS for approval, duplicate tap protection, pending retry, and archived result.

```bash
git add app/src/main app/src/test
git commit -m "feat: connect explicit approval and cleanup"
```

---

### Task 5: Archive, editing, search, copy, delete, and settings UI

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/ui/archive/ArchiveUiState.kt`
- Create: `app/src/main/java/com/capo/diarioclase/ui/archive/ArchiveViewModel.kt`
- Create: `app/src/main/java/com/capo/diarioclase/ui/archive/ArchiveScreen.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/MainActivity.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureScreen.kt`
- Test: `app/src/test/java/com/capo/diarioclase/ui/archive/ArchiveViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 `DiaryRepository` and `DiaryClipboardFormatter`.
- Produces: repository-backed home/archive navigation with persistent local settings.

- [ ] **Step 1: Write failing archive behavior tests**

```kotlin
@Test fun `query filters date and all five fields`() = runTest {
    viewModel.onQuery("tarea seis")
    assertEquals(listOf("entry-1"), viewModel.state.value.entries.map { it.id })
}

@Test fun `editing an archived entry persists`() = runTest {
    viewModel.saveEdit(entry.copy(homework="Ejercicio 8"))
    assertEquals("Ejercicio 8", repository.entry("entry-1").homework)
}
```

- [ ] **Step 2: Run test and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*ArchiveViewModelTest'`

Expected: compilation fails because archive types are absent.

- [ ] **Step 3: Implement archive view model**

Expose query, selected diary, editable fields, interpretation mode, message, and busy state. Delete requires a selected immutable diary ID and a separate confirmation callback.

- [ ] **Step 4: Implement rectangular monochrome archive screen**

Home shows `NUEVO DÍA`, `DIARIOS GUARDADOS`, search, diary cards, and `CONFIGURACIÓN`. Detail view exposes `EDITAR`, `GUARDAR`, `COPIAR`, and `ELIMINAR DIARIO`. Clipboard uses only `DiaryClipboardFormatter`.

- [ ] **Step 5: Implement settings panel**

Show three choices with consequences. Selecting one persists immediately through `setMode`; default selection is conservative. No network, token, or cloud controls are rendered.

- [ ] **Step 6: Connect screen selection in MainActivity**

Use a small sealed `AppScreen` state containing `Capture`, `Archive`, and `Settings`. Business state remains in repositories and survives activity recreation; screen state only chooses which repository-backed view is visible.

- [ ] **Step 7: Run archive and existing view-model tests**

Run: `./gradlew testDebugUnitTest --tests '*ArchiveViewModelTest' --tests '*CaptureViewModelTest' --tests '*DiaryClipboardFormatterTest'`

Expected: PASS for filtering, editing, copying, confirmed deletion, and settings persistence.

- [ ] **Step 8: Commit**

```bash
git add app/src/main app/src/test
git commit -m "feat: add editable local diary archive"
```

---

### Task 6: Recovery, migration validation, and installable phase 3 APK

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Modify: `app/build.gradle.kts`
- Create: `PHASE3_DEVICE_TEST.md`
- Test: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`

**Interfaces:**
- Consumes: all previous tasks.
- Produces: restart recovery, version `0.3.0-phase3`, verified APK, source archive, and empirical test guide.

- [ ] **Step 1: Write failing full-journey test**

```kotlin
@Test fun `review approval cleanup archive edit search and copy`() = runTest {
    val draft = processedDraft()
    assertIs<CleanupOutcome.Archived>(cleanup.approveAndClean(SessionId("s"), draft))
    archive.saveEdit(archive.entry("s").copy(topics="Pasados y narración"))
    assertEquals(1, archive.observeEntries("narracion").first().size)
    assertFalse(audioFiles.any { it.exists() })
    assertEquals(0, dao.temporaryRowCount("s"))
}
```

- [ ] **Step 2: Run test and verify RED**

Run: `./gradlew testDebugUnitTest --tests '*FullJourneyTest'`

Expected: fails until restart recovery and complete wiring are present.

- [ ] **Step 3: Add startup cleanup recovery**

On application start, query `CLEANUP_PENDING` without deleting anything automatically. Repository flows surface the permanent diary and retry action. This avoids irreversible background work without the user seeing its result.

- [ ] **Step 4: Set package version**

Set `versionCode = 4` and `versionName = "0.3.0-phase3"`. Keep application ID and `signing/diario-clase-debug.keystore` unchanged.

- [ ] **Step 5: Write empirical Markdown guide**

`PHASE3_DEVICE_TEST.md` instructs installing over `Diario de clase 2`, making a short synthetic recording, processing, editing, approving, verifying playback is unavailable after cleanup, searching, editing again, and copying. It states not to uninstall the app.

- [ ] **Step 6: Run complete verification**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug`

Expected: exit code 0, every unit test passes, lint reports 0 errors, and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 7: Verify package and signing identity**

Run:

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

Expected: package `com.capo.diarioclase.phase2`, version code 4, and certificate SHA-256 `4f8411ea4bc7940fa03100fa9d232a41f8f41391a896ebc4c4f5e0e8c7b47fd2`.

- [ ] **Step 8: Commit**

```bash
git add app PHASE3_DEVICE_TEST.md
git commit -m "release: prepare phase three empirical APK"
```

# Phase 5.2 Integrity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver `0.5.2-integrity` with correct chronology, field routing, claim identity, evidence persistence, semantic state recovery and zero-network mode changes.

**Architecture:** Preserve the existing free-provider router but separate interpretation state from Whisper, namespace all provider claims, persist the complete evidence graph and materialize fields through one policy-aware component. Every remote operation has a bounded deadline and every mode change reprojects stored claims locally.

**Tech Stack:** Kotlin, Android, Jetpack Compose, Room, WorkManager, kotlinx.coroutines, JUnit, Android instrumented migration tests, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-15-phase5-2-quality-design.md`

## Global Constraints

- Base implementation is `main@63c4be6cfe5c07f42dd91d8f6948b194e276da56`.
- Integration branch is `feature/phase5-2-quality`.
- Audio never leaves the device.
- Remote inference remains disabled by default.
- Allowed providers remain Gemini, Groq and OpenRouter `openrouter/free`.
- No automatic paid model or route is permitted.
- CI uses fake transports and fake credentials only.
- A semantic failure never changes a `TRANSCRIBED` audio segment to `FAILED`.
- Changing interpretation mode performs zero HTTP requests.
- Existing user-edited fields are never overwritten.
- Do not integrate a task until its task tests and GitHub Actions are green.

---

### Task I0: Correct continuity and establish the baseline

**Branch:** `feature/phase5-2-task-i0-readiness`

**Files:**
- Modify: `AGENTS.md`
- Modify: `PHASE5_HANDOFF.md`
- Modify: `PHASE5_FREE_INFERENCE_DEVICE_TEST.md`
- Modify: `.github/workflows/android-apk.yml`

**Interfaces:**
- Consumes: `main@63c4be6` and PR #15.
- Produces: authoritative branch, release and PR instructions for every later agent.

- [ ] **Step 1: Write continuity assertions**

Run:

```bash
rg -n "feature/phase5-contextual-interpretation|0\.5\.0-free-router|código de producción todavía no implementado" AGENTS.md PHASE5_HANDOFF.md PHASE5_FREE_INFERENCE_DEVICE_TEST.md
```

Expected: matches demonstrate stale continuity.

- [ ] **Step 2: Replace stale source-of-truth values**

Set:

```text
Base estable: main@63c4be6
Rama de integración: feature/phase5-2-quality
PR de diseño: #15
Versión instalada pendiente de prueba: 0.5.1-free-router, versionCode 8
Siguiente entrega: 0.5.2-integrity
Plan: docs/superpowers/plans/2026-09-15-phase5-2-integrity.md
Colaboración: PHASE5_2_AGENT_COLLABORATION.md
```

All task PRs target `feature/phase5-2-quality`.

- [ ] **Step 3: Add CI triggers**

Add `feature/phase5-2-quality` and `feature/phase5-2-task-*` to push triggers, and allow pull requests targeting `feature/phase5-2-quality`.

- [ ] **Step 4: Verify documentation**

Run:

```bash
rg -n "feature/phase5-contextual-interpretation|0\.5\.0-free-router|código de producción todavía no implementado" AGENTS.md PHASE5_HANDOFF.md PHASE5_FREE_INFERENCE_DEVICE_TEST.md
rg -n "feature/phase5-2-quality|0\.5\.1-free-router|0\.5\.2-integrity" AGENTS.md PHASE5_HANDOFF.md PHASE5_FREE_INFERENCE_DEVICE_TEST.md
```

Expected: first command returns no stale operational instruction; second command finds the new source of truth.

- [ ] **Step 5: Commit**

```bash
git add AGENTS.md PHASE5_HANDOFF.md PHASE5_FREE_INFERENCE_DEVICE_TEST.md .github/workflows/android-apk.yml
git commit -m "docs: establish phase 5.2 integration baseline"
```

### Task I1: Freeze identity and semantic-state contracts

**Branch:** `feature/phase5-2-task-i1-contracts`

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/processing/semantic/InferenceModels.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/evidence/EvidenceModels.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/ClaimIdentity.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/work/InterpretationRunModels.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/semantic/ClaimIdentityTest.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/work/InterpretationRunModelsTest.kt`

**Interfaces:**
- Consumes: `InterpretationRequest.packetId`, `InferenceProvider`, `providerClaimKey`.
- Produces: `ClaimIdentity.id(runId, packetId, provider, providerClaimKey): String`, `InterpretationPacket(request, sourceSpanIds)`, `InterpretationRunState`, `InterpretationPacketState`, `InterpretationFailure`, and expanded claim metadata.

- [ ] **Step 1: Write failing identity tests**

```kotlin
@Test fun same_provider_key_in_two_packets_never_collides() {
    val a = ClaimIdentity.id("run", "packet-a", InferenceProvider.GEMINI, "C1")
    val b = ClaimIdentity.id("run", "packet-b", InferenceProvider.GEMINI, "C1")
    assertNotEquals(a, b)
}

@Test fun identity_is_stable() {
    assertEquals(
        ClaimIdentity.id("run", "packet", InferenceProvider.GROQ, "C1"),
        ClaimIdentity.id("run", "packet", InferenceProvider.GROQ, "C1"),
    )
}
```

- [ ] **Step 2: Run tests and confirm failure**

Run:

```bash
./gradlew testDebugUnitTest --tests '*ClaimIdentityTest' --tests '*InterpretationRunModelsTest'
```

Expected: FAIL because the new contracts do not exist.

- [ ] **Step 3: Implement the contracts**

Use SHA-256 over length-prefixed values:

```kotlin
object ClaimIdentity {
    fun id(
        runId: String,
        packetId: String,
        provider: InferenceProvider,
        providerClaimKey: String,
    ): String = sha256(
        listOf(runId, packetId, provider.name, providerClaimKey)
            .joinToString("|") { "${it.length}:$it" },
    )
}

enum class InterpretationRunState {
    PENDING, RUNNING, REMOTE_OK, LOCAL_OK, MIXED_OK, FAILED, CANCELLED
}

enum class InterpretationPacketState {
    PENDING, RUNNING, REMOTE_OK, LOCAL_OK, FAILED, CANCELLED
}

enum class InterpretationFailure {
    DEADLINE, CANCELLED, TRANSPORT, INVALID_RESPONSE, INSUFFICIENT_RESPONSE, INTERNAL
}
```

Add a local wrapper that is never serialized by provider clients:

```kotlin
data class InterpretationPacket(
    val request: InterpretationRequest,
    val sourceSpanIds: Map<String, String>,
) {
    init {
        require(request.spans.map { it.publicId }.toSet() == sourceSpanIds.keys)
    }
}
```

`InterpretationPacketBuilder.build` returns these wrappers. Provider clients receive only `packet.request`; validators and persistence receive the wrapper so every public evidence id can be mapped back to a real `transcriptSpanId`.

Expand `RawClaim` and `EvidenceClaim` with defaults for `runId`, `packetId`, `providerClaimKey`, `declaredConfidence`, `effectiveConfidence`, and local evidence span ids. Preserve source compatibility.

- [ ] **Step 4: Run contract tests**

Run:

```bash
./gradlew testDebugUnitTest --tests '*ClaimIdentityTest' --tests '*InterpretationRunModelsTest' --tests '*InferenceContractTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing app/src/test/java/com/capo/diarioclase/processing
git commit -m "feat: define global claim and interpretation state contracts"
```

### Task I2: Preserve chronology and enforce packet budgets

**Branch:** `feature/phase5-2-task-i2-packets`

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/processing/semantic/InterpretationPacketBuilder.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/semantic/InterpretationPacketBuilderTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/InterpretationPacketBudgetPropertyTest.kt`

**Interfaces:**
- Consumes: transcript order from `SessionDao.transcript`.
- Produces: `InterpretationPacket` values whose public requests preserve block→segment→span order, whose private bindings map every public id to a local transcript span, and whose rendered request fits `maxRequestBytes`.

- [ ] **Step 1: Write the chronology regression**

```kotlin
@Test fun segment_clock_reset_does_not_interleave_segments() {
    val spans = listOf(
        span(id = "a1", segment = "A", startMs = 8_000, text = "página veinte"),
        span(id = "a2", segment = "A", startMs = 9_000, text = "ejercicio dos"),
        span(id = "b1", segment = "B", startMs = 0, text = "no, el tres"),
    )
    val result = InterpretationPacketBuilder().build(spans).flatMap { it.spans }
        .filterNot { it.contextOnly }.map { it.text }
    assertEquals(listOf("página veinte", "ejercicio dos", "no, el tres"), result)
}
```

- [ ] **Step 2: Confirm the old implementation fails**

Run:

```bash
./gradlew testDebugUnitTest --tests '*InterpretationPacketBuilderTest.segment_clock_reset_does_not_interleave_segments'
```

Expected: FAIL because `b1` is sorted before segment A.

- [ ] **Step 3: Preserve input segment order**

Replace block-wide `startMs` sorting with a stable traversal that keeps first-appearance segment order and sorts only spans belonging to the same segment:

```kotlin
private fun orderBlock(spans: List<IndexedValue<TranscriptSpan>>): List<TranscriptSpan> =
    spans.groupByTo(LinkedHashMap()) { it.value.audioSegmentId }
        .values.flatMap { segment ->
            segment.sortedWith(compareBy({ it.value.startMs }, { it.index })).map { it.value }
        }
```

Calculate the request budget through the same renderer used by provider clients. Reject no span: split long text into deterministic pieces carrying the same local source identity.

- [ ] **Step 4: Add and run budget properties**

Generate Unicode, quotes, newlines, overlaps and a 20,000-character span. Assert:

```kotlin
assertTrue(renderer.estimatedBytes(packet) <= maxRequestBytes)
assertEquals(originalText, rebuildText(packets))
```

Run:

```bash
./gradlew testDebugUnitTest --tests '*InterpretationPacketBuilderTest' --tests '*InterpretationPacketBudgetPropertyTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/semantic/InterpretationPacketBuilder.kt app/src/test/java/com/capo/diarioclase/processing/semantic
git commit -m "fix: preserve segment chronology and bound inference packets"
```

### Task I3: Materialize fields through status policy

**Branch:** `feature/phase5-2-task-i3-projection`

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/DiaryFieldMaterializer.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/PagesAndExercisesComposer.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/evidence/InterpretationProjector.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionCoordinator.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/evidence/DiaryFieldMaterializerTest.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/evidence/PagesAndExercisesComposerTest.kt`

**Interfaces:**
- Consumes: `StatusFieldPolicy.target(category, status)` and projected claims.
- Produces: `DiaryFieldMaterializer.materialize(sessionId, mode, claims): DiaryDraft`.

- [ ] **Step 1: Write the assigned-exercise regression**

```kotlin
@Test fun assigned_exercise_goes_to_homework_only() {
    val draft = materializer.materialize(
        "s", InterpretationMode.CONSERVATIVE,
        listOf(claim(category = EXERCISE, status = ASSIGNED, value = "4", confidence = 1.0)),
    )
    assertEquals("4", draft.homework)
    assertFalse(draft.pages.contains("4"))
}
```

Add cases for a page reset at a new block and for a cancelled page that must not provide context.

- [ ] **Step 2: Confirm failure**

Run:

```bash
./gradlew testDebugUnitTest --tests '*DiaryFieldMaterializerTest' --tests '*PagesAndExercisesComposerTest'
```

Expected: FAIL because production currently filters directly by category.

- [ ] **Step 3: Implement one materializer**

```kotlin
class DiaryFieldMaterializer(
    private val projector: InterpretationProjector,
    private val composer: PagesAndExercisesComposer,
) {
    fun materialize(
        sessionId: String,
        mode: InterpretationMode,
        claims: List<EvidenceClaim>,
    ): DiaryDraft {
        val presentation = projector.project(claims, mode)
        val acceptedByField = presentation.accepted.groupBy {
            (StatusFieldPolicy.target(it.category, it.status) as FieldTarget.Field).field
        }
        return DiaryDraft(
            sessionId = sessionId,
            mode = mode,
            topics = values(acceptedByField[DiaryField.TOPICS]),
            activities = values(acceptedByField[DiaryField.ACTIVITIES]),
            pages = composer.compose(presentation.accepted),
            exercises = "",
            homework = values(acceptedByField[DiaryField.HOMEWORK]),
            accepted = presentation.accepted,
            confirm = presentation.confirm,
        )
    }
}
```

The composer sorts by block and evidence order, resets the active page on block change and ignores inactive-history claims.

- [ ] **Step 4: Replace coordinator field assembly and run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests '*DiaryFieldMaterializerTest' --tests '*PagesAndExercisesComposerTest' --tests '*TranscriptionCoordinatorTest'
```

Expected: PASS, including `EXERCISE + ASSIGNED → Tarea`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/evidence app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionCoordinator.kt app/src/test/java/com/capo/diarioclase/processing
git commit -m "fix: materialize diary fields from semantic status"
```

### Task I4: Persist the semantic graph and migrate Room 5→6

**Branch:** `feature/phase5-2-task-i4-persistence`

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/RoomProcessingStore.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/work/RoomInterpretationJournal.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/data/db/DiarioMigrationTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/work/RoomInterpretationJournalTest.kt`
- Create: `app/schemas/com.capo.diarioclase.data.db.DiarioDatabase/6.json`

**Interfaces:**
- Consumes: contracts from I1.
- Produces: `InterpretationJournal` with `beginRun`, `startPacket`, `recordAttempt`, `completePacket`, `failRun`, `completeRun` and `loadClaims`.

- [ ] **Step 1: Write a populated migration test**

Create v5 rows containing one session, two claims with the same provider-style key in different packets, a draft and cache. Migrate to v6 and assert old data survives and new tables are empty.

```kotlin
helper.runMigrationsAndValidate(DB_NAME, 6, true, DiarioDatabase.MIGRATION_5_6)
```

- [ ] **Step 2: Write the evidence round-trip test**

```kotlin
@Test fun evidence_and_supersessions_survive_reopen() = runTest {
    journal.saveClaims(listOf(claimWithTwoEvidenceAndSupersession()))
    val loaded = journal.loadClaims("run-1")
    assertEquals(2, loaded.single().evidences.size)
    assertEquals(listOf("claim-old"), loaded.single().supersedesClaimIds)
}
```

Run both tests. Expected: FAIL because schema v6 and journal do not exist.

- [ ] **Step 3: Add normalized entities**

Add entities with foreign keys and compound indices:

```kotlin
@Entity(tableName = "interpretation_runs", indices = [Index("sessionId")])
data class InterpretationRunEntity(/* ids, versions, state, timing, provenance, failure */)

@Entity(
    tableName = "interpretation_packets",
    primaryKeys = ["runId", "packetId"],
    indices = [Index("runId")],
)
data class InterpretationPacketEntity(/* ordinal, state, budget and timestamps */)

@Entity(tableName = "provider_attempts", indices = [Index("runId", "packetId")])
data class ProviderAttemptEntity(/* provider, model, attempt, cacheHit, outcome, duration */)

@Entity(primaryKeys = ["claimId", "transcriptSpanId"], tableName = "claim_evidence")
data class ClaimEvidenceEntity(val claimId: String, val transcriptSpanId: String, val ordinal: Int, val contextual: Boolean)

@Entity(primaryKeys = ["newClaimId", "oldClaimId"], tableName = "claim_supersessions")
data class ClaimSupersessionEntity(val newClaimId: String, val oldClaimId: String)
```

Add namespaced metadata columns to `EvidenceClaimEntity`. Use explicit SQL in `MIGRATION_5_6`; do not use destructive migration. Set `exportSchema = true`.

- [ ] **Step 4: Implement transactional journal methods and run tests**

No journal method accepts raw HTTP bodies, headers or credentials. Persist only typed outcomes.

Run:

```bash
./gradlew testDebugUnitTest --tests '*RoomInterpretationJournalTest'
./gradlew testDebugUnitTest --tests '*DiarioMigrationTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/data app/src/main/java/com/capo/diarioclase/processing/work app/src/test app/src/androidTest app/schemas
git commit -m "feat: persist semantic runs claims and evidence"
```

### Task I5: Bound runtime and separate semantic failures

**Branch:** `feature/phase5-2-task-i5-runtime`

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/processing/semantic/FreeInferenceRouter.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/semantic/InferenceHttpTransport.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/semantic/ProviderRetryPolicy.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/semantic/RouterSemanticInterpreter.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionWorker.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/semantic/FreeInferenceRouterTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/work/TranscriptionWorkerTest.kt`

**Interfaces:**
- Consumes: `InterpretationJournal` from I4, or an in-memory fake during branch development.
- Produces: `InterpretationBudget(packetMs = 60_000, sessionMs = 120_000)` and semantic terminal outcomes independent of `TranscriptionFailure`.

- [ ] **Step 1: Write virtual-time deadline tests**

```kotlin
@Test fun session_deadline_falls_back_without_marking_audio_failed() = runTest {
    val result = interpreter.interpret(session, spans, InterpretationBudget(60_000, 120_000))
    assertIs<InterpretationOutcome.LocalOrMixed>(result)
    assertEquals(SegmentState.TRANSCRIBED, store.segmentState(segment))
    assertTrue(testScheduler.currentTime <= 120_000)
}
```

Add exceptions from cache, credential store and journal; each must produce a semantic failure or local result, never a phantom `PROCESSING` state.

- [ ] **Step 2: Confirm failures**

Run:

```bash
./gradlew testDebugUnitTest --tests '*FreeInferenceRouterTest' --tests '*TranscriptionWorkerTest'
```

Expected: FAIL on missing budget and semantic outcomes.

- [ ] **Step 3: Implement bounded routing**

Wrap packet and session work with cooperative `withTimeoutOrNull`. Track two consecutive transient failures per provider and open its execution circuit. Honor `retryAfterMs` only when it fits the remaining budget.

The worker catches semantic exceptions separately:

```kotlin
val transcriptResult = withTimeout(TRANSCRIPTION_TIMEOUT_MS) { coordinator.transcribeNext(id) }
val interpretationResult = withTimeoutOrNull(SEMANTIC_SESSION_TIMEOUT_MS) {
    coordinator.interpretNext(id)
} ?: coordinator.continueLocally(id, InterpretationFailure.DEADLINE)
```

Never call `failCurrent` for an interpretation failure.

Make the HTTP transport cooperatively cancellable. Hold the active `HttpsURLConnection` inside `suspendCancellableCoroutine`, run blocking I/O on `Dispatchers.IO`, and register:

```kotlin
continuation.invokeOnCancellation { connection.disconnect() }
```

A cancellation test uses a fake blocking connection and asserts `disconnect()` is called within 1 s.

- [ ] **Step 4: Run runtime and regression tests**

```bash
./gradlew testDebugUnitTest --tests '*FreeInferenceRouterTest' --tests '*ProviderRetryPolicyTest' --tests '*TranscriptionWorkerTest' --tests '*TranscriptionCoordinatorTest'
```

Expected: PASS; worst-case virtual time stays within 120 s.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing app/src/test/java/com/capo/diarioclase/processing
git commit -m "fix: bound inference and isolate semantic failures"
```

### Task I6: Build an executable integrity evaluation gate

**Branch:** `feature/phase5-2-task-i6-evaluation`

**Files:**
- Create: `app/src/test/java/com/capo/diarioclase/processing/evaluation/SemanticExpectation.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/evaluation/SemanticEvaluationRunner.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/semantic/SyntheticInterpretationScenarios.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/evaluation/SemanticIntegrityEvaluationTest.kt`

**Interfaces:**
- Consumes: synthetic transcripts, actual claims and actual `DiaryDraft`.
- Produces: typed positive/negative expectations and a deterministic failure report.

- [ ] **Step 1: Replace prose prohibitions with types**

```kotlin
data class ClaimExpectation(
    val category: ClaimCategory,
    val normalizedValue: String,
    val status: ClaimStatus,
)

data class SemanticExpectation(
    val required: Set<ClaimExpectation>,
    val prohibited: Set<ClaimExpectation>,
    val expectedFields: Map<DiaryField, String>,
)
```

- [ ] **Step 2: Write failing end-to-end scenarios**

Include:

- clock reset across segments;
- `EXERCISE + ASSIGNED`;
- repeated `C1` keys in two packets;
- a question that must not become an activity;
- a corrected page;
- prompt-injection text treated as transcript data.

Run:

```bash
./gradlew testDebugUnitTest --tests '*SemanticIntegrityEvaluationTest'
```

Expected: FAIL against pre-integrity code.

- [ ] **Step 3: Implement exact evaluation**

Canonicalize by `category + normalizedValue + status`. Report missing required claims, prohibited claims present, wrong fields and evidence without valid span identity. Do not call real providers.

- [ ] **Step 4: Run evaluation and all semantic tests**

```bash
./gradlew testDebugUnitTest --tests '*processing.semantic*' --tests '*processing.evidence*' --tests '*processing.evaluation*'
```

Expected: PASS after I2–I4 are integrated.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/capo/diarioclase/processing
git commit -m "test: add executable semantic integrity gate"
```

### Task I7: Reproject locally and expose semantic state

**Branch:** `feature/phase5-2-task-i7-ui-state`

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureViewModel.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureUiState.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureScreen.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/RoomProcessingStore.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/work/LocalDraftReprojector.kt`
- Test: `app/src/test/java/com/capo/diarioclase/ui/capture/CaptureViewModelTest.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/work/LocalDraftReprojectorTest.kt`

**Interfaces:**
- Consumes: persisted claims from I4 and materializer from I3.
- Produces: `LocalDraftReprojector.reproject(sessionId, mode)`, observable semantic progress and Continue local action.

- [ ] **Step 1: Write the zero-network mode test**

```kotlin
@Test fun changing_mode_reprojects_without_scheduling_work() = runTest {
    viewModel.onMode(InterpretationMode.EXHAUSTIVE)
    assertEquals(0, scheduler.enqueueCount)
    assertEquals(0, fakeProvider.calls)
    assertEquals(InterpretationMode.EXHAUSTIVE, store.savedDraft.mode)
}
```

- [ ] **Step 2: Write state-action tests**

Assert that RUNNING exposes package/provider, FAILED exposes Retry and Continue local, and Continue local cancels further provider calls before executing local fallback.

- [ ] **Step 3: Implement local reprojection**

```kotlin
class LocalDraftReprojector(
    private val store: ProcessingStore,
    private val materializer: DiaryFieldMaterializer,
) {
    suspend fun reproject(sessionId: SessionId, mode: InterpretationMode): DiaryDraft {
        val claims = store.persistedClaims(sessionId)
        val generated = materializer.materialize(sessionId.value, mode, claims)
        return store.mergeFieldEditsAndSave(generated)
    }
}
```

Change `CaptureViewModel.onMode` to call the reprojector, never `resumeProcessing`.

- [ ] **Step 4: Run UI-state tests**

```bash
./gradlew testDebugUnitTest --tests '*CaptureViewModelTest' --tests '*LocalDraftReprojectorTest'
```

Expected: PASS; provider and scheduler counters remain zero.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/ui/capture app/src/main/java/com/capo/diarioclase/processing/work app/src/test/java/com/capo/diarioclase
git commit -m "feat: reproject modes locally and expose semantic state"
```

### Task I8: Integrate and release 0.5.2-integrity

**Branch:** `feature/phase5-2-task-i8-release`

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `PHASE5_HANDOFF.md`
- Modify: `PHASE5_FREE_INFERENCE_DEVICE_TEST.md`
- Modify: `AGENTS.md`
- Modify: `.github/workflows/android-apk.yml`
- Test: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`

**Interfaces:**
- Consumes: I2–I7 integrated and green.
- Produces: versionCode 9, versionName `0.5.2-integrity`, APK and device protocol.

- [ ] **Step 1: Add the full-journey regression**

The test uses two audio segments with reset clocks, duplicated provider keys, one performed exercise and one assigned exercise. Assert chronology, field placement, evidence after simulated reopen and zero provider calls after mode change.

- [ ] **Step 2: Run the complete verification**

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
```

Expected: exit 0.

- [ ] **Step 3: Update version and protocol**

Set:

```kotlin
versionCode = 9
versionName = "0.5.2-integrity"
```

The Moto g max protocol covers chronology, assigned homework, mode change offline, cache reopen, semantic timeout, Continue local, pause and kill/restart. Do not approve/delete the session until evidence persistence has been checked.

- [ ] **Step 4: Verify APK and secrets**

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'ggml-base.bin|lib/arm64-v8a/libdiarioclase_whisper.so'
rg -n --hidden --glob '!build/**' --glob '!.git/**' 'AIza|gsk_|sk-or-v1-' .
```

Expected: model and native library present; no real credential match.

- [ ] **Step 5: Commit and wait for CI**

```bash
git add app/build.gradle.kts PHASE5_HANDOFF.md PHASE5_FREE_INFERENCE_DEVICE_TEST.md AGENTS.md .github/workflows/android-apk.yml app/src/test
git commit -m "release: prepare 0.5.2 integrity validation"
```

Do not merge to `main` until GitHub Actions is green and the physical protocol is recorded.

# Second-Pass Editorial Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar una segunda etapa de IA que transforme la evidencia aceptada en una ficha final con resumen, material trabajado y tarea, sin aceptar respuestas que pierdan páginas, ejercicios o tareas.

**Architecture:** La primera interpretación y la revisión humana permanecen como fuente de verdad. Un paquete editorial canónico envía únicamente claims aceptados a la cadena gratuita; la respuesta vincula cada fragmento redactado con sus claims de origen y un validador local controla esquema, cobertura e integridad literal. Room persiste el resultado editorial antes de que la limpieza pueda borrar audio, transcripción o evidencia.

**Tech Stack:** Kotlin 2.x, Android SDK 35, Jetpack Compose, Room 8→9, WorkManager, coroutines, `kotlinx-serialization-json`, JUnit 4 y Robolectric.

**Spec:** `docs/superpowers/specs/2026-09-21-second-pass-report-design.md`

## Global Constraints

- El audio permanece local y nunca se envía a un proveedor.
- La segunda etapa recibe únicamente claims de `projection.accepted`; un marcador manual crudo no se envía porque no contiene texto.
- Proveedores permitidos y orden: Gemini, Groq y OpenRouter `openrouter/free`; nunca modelos pagos.
- La ficha visible proviene directamente de la respuesta editorial validada; el cliente no recompone su prosa.
- Toda página, ejercicio y tarea aceptados deben estar enlazados a una salida visible apropiada.
- Una respuesta inválida o incompleta permite una reparación por proveedor y nunca se guarda como éxito.
- Aceptar, rechazar, corregir o cambiar de modo invalida atómicamente la ficha editorial anterior.
- No se borra ningún temporal hasta persistir y releer una ficha editorial `READY` correspondiente a la evidencia actual.
- La ficha anterior permanece disponible solo para comparación durante la APK experimental.
- Ninguna prueba automática usa red ni credenciales reales.

## Review Focus

- Dos claims distintos con el mismo texto deben conservar ambos ids y no desaparecer por una deduplicación textual; lo cubre Task 1.
- Una respuesta tardía generada con un hash anterior no debe reemplazar una ficha invalidada por una revisión; lo cubre Task 5.
- Un ejercicio `ASSIGNED` debe aparecer en tarea y nunca exclusivamente en material; lo cubre Task 2.
- Una migración desde una base v8 con diarios existentes debe conservarlos y dejarlos visibles mediante el formato legacy; lo cubre Task 4.
- La aprobación durante estado `STALE`, `FAILED` o `GENERATING` debe fallar sin borrar audio ni evidencia; lo cubre Task 7.

---

## Mapa de archivos

### Nuevos archivos de producción

- `processing/editorial/EditorialReportModels.kt`: contrato del paquete, respuesta, estados y resultados.
- `processing/editorial/EditorialReportPacketBuilder.kt`: orden canónico, proyección aceptada y hash de entrada.
- `processing/editorial/EditorialReportCodec.kt`: JSON cerrado de salida y metadatos de auditoría.
- `processing/editorial/EditorialReportPromptFactory.kt`: prompt y JSON Schema de la segunda etapa.
- `processing/editorial/EditorialReportValidator.kt`: cobertura por id, destino y literales numéricos.
- `processing/editorial/EditorialProviderClient.kt`: interfaz específica de proveedor editorial.
- `processing/editorial/GeminiEditorialProviderClient.kt`: adaptador Gemini.
- `processing/editorial/OpenAiEditorialProviderClient.kt`: adaptador Groq/OpenRouter.
- `processing/editorial/EditorialReportRouter.kt`: cadena secuencial, reparación y fallas tipadas.
- `processing/editorial/EditorialReportService.kt`: caché, estado, deadline y defensa contra respuestas obsoletas.
- `processing/editorial/RoomEditorialReportStore.kt`: persistencia Room y transición `GENERATING/READY/STALE/FAILED`.
- `SECOND_PASS_REPORT_DEVICE_TEST.md`: protocolo físico para el Moto g max.

### Archivos de producción modificados

- `data/db/Entities.kt`, `SessionDao.kt`, `DiarioDatabase.kt`: esquema Room 9.
- `data/repository/RoomDiaryRepository.kt`, `diary/DiaryModels.kt`: copia permanente del informe editorial.
- `diary/DiaryClipboardFormatter.kt`, `diary/cleanup/CleanupCoordinator.kt`: copiado y condición de aprobación.
- `processing/work/TranscriptionCoordinator.kt`, `RoomProcessingStore.kt`: generación inicial e invalidación.
- `AndroidCaptureActions.kt`, `DiarioClaseApp.kt`: composición y acciones.
- `ui/capture/CaptureUiState.kt`, `CaptureViewModel.kt`, `CaptureScreen.kt`: estado y UI editorial.
- `ui/archive/ArchiveViewModel.kt`, `ArchiveScreen.kt`: lectura y edición de diarios nuevos sin romper legacy.
- `app/build.gradle.kts`: versión experimental.

## Task 1: Contrato y paquete editorial canónico

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/EditorialReportModels.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/EditorialReportPacketBuilder.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/editorial/EditorialReportPacketBuilderTest.kt`

**Interfaces:**
- Consumes: `EvidenceClaim`, `InterpretationProjector`, `InterpretationMode`, `StatusFieldPolicy`.
- Produces: `EditorialReportRequest`, `EditorialEvidenceItem`, `EditorialSection`, `EditorialReport`, `EditorialOutputItem`, `EditorialDiscard`, `EditorialReportState` y `EditorialReportPacketBuilder.build(sessionId, mode, claims)`.

- [ ] **Step 1: Escribir pruebas fallidas del orden, la selección y el hash**

```kotlin
@Test fun `packet contains only accepted active claims in evidence order`() {
    val request = builder.build("session", InterpretationMode.CONSERVATIVE, listOf(confirm, acceptedExercise, acceptedPage))
    assertEquals(listOf(acceptedPage.id, acceptedExercise.id), request.items.map { it.claimId })
}

@Test fun `same text with different claim ids remains twice`() {
    val request = builder.build("session", InterpretationMode.CONSERVATIVE, listOf(first, second))
    assertEquals(listOf(first.id, second.id), request.items.map { it.claimId })
}

@Test fun `input hash changes after claim correction`() {
    val before = builder.build("session", mode, listOf(claim.copy(value = "Ejercicio 3")))
    val after = builder.build("session", mode, listOf(claim.copy(value = "Ejercicio 4")))
    assertNotEquals(before.inputHash, after.inputHash)
}
```

- [ ] **Step 2: Ejecutar las pruebas y verificar que fallen**

Run: `./gradlew testDebugUnitTest --tests '*EditorialReportPacketBuilderTest'`

Expected: FAIL porque no existen los modelos ni el builder.

- [ ] **Step 3: Crear los modelos inmutables**

```kotlin
enum class EditorialSection { SUMMARY, MATERIAL, HOMEWORK }
enum class EditorialReportState { GENERATING, READY, STALE, FAILED }

data class EditorialEvidenceItem(
    val claimId: String,
    val category: ClaimCategory,
    val status: ClaimStatus,
    val value: String,
    val normalizedValue: String,
    val excerpt: String,
    val blockOrdinal: Int?,
    val segmentOrdinal: Int?,
    val spanOrdinal: Int?,
    val origin: ClaimOrigin,
)

data class EditorialReportRequest(
    val sessionId: String,
    val inputHash: String,
    val promptVersion: String = "editorial-prompt-v1",
    val schemaVersion: String = "editorial-schema-v1",
    val items: List<EditorialEvidenceItem>,
)

data class EditorialOutputItem(val text: String, val sourceClaimIds: List<String>)
data class EditorialDiscard(val claimId: String, val reason: String)
data class EditorialReport(
    val summary: String,
    val material: List<EditorialOutputItem>,
    val homework: List<EditorialOutputItem>,
    val summarySourceClaimIds: List<String>,
    val discarded: List<EditorialDiscard>,
)
```

- [ ] **Step 4: Implementar `EditorialReportPacketBuilder`**

El builder debe proyectar con `InterpretationProjector`, ordenar por evidencia y calcular SHA-256 sobre una representación canónica que incluya id, categoría, estado, valor, valor normalizado, excerpt y ordinales. No debe deduplicar por texto.

```kotlin
class EditorialReportPacketBuilder(
    private val projector: InterpretationProjector = InterpretationProjector(),
) {
    fun build(sessionId: String, mode: InterpretationMode, claims: List<EvidenceClaim>): EditorialReportRequest {
        val items = projector.project(claims, mode).accepted
            .sortedWith(compareBy({ primary(it).blockOrdinal }, { primary(it).audioSegmentOrdinal }, { primary(it).spanOrdinal }, { it.claimOrdinal }))
            .map(::toItem)
        return EditorialReportRequest(sessionId, hash(items), items = items)
    }
}
```

- [ ] **Step 5: Ejecutar las pruebas específicas**

Run: `./gradlew testDebugUnitTest --tests '*EditorialReportPacketBuilderTest'`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/editorial app/src/test/java/com/capo/diarioclase/processing/editorial
git commit -m "feat: define canonical editorial evidence packet"
```

## Task 2: Códec, prompt y validador de cobertura

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/EditorialReportCodec.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/EditorialReportPromptFactory.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/EditorialReportValidator.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/editorial/EditorialReportCodecTest.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/editorial/EditorialReportPromptFactoryTest.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/editorial/EditorialReportValidatorTest.kt`

**Interfaces:**
- Consumes: `EditorialReportRequest` y `EditorialReport` de Task 1.
- Produces: `EditorialPrompt`, `EditorialReportCodec.decode`, `EditorialValidationOutcome`, `EditorialIssue` y `EditorialReportValidator.validate(rawJson, request)`.

- [ ] **Step 1: Escribir pruebas fallidas del contrato cerrado**

```kotlin
@Test fun `codec rejects unknown root properties`() {
    assertFailsWith<EditorialContractException> {
        EditorialReportCodec.decode(validJson.replaceFirst("{", "{\"extra\":true,"))
    }
}

@Test fun `validator rejects missing page claim coverage`() {
    val outcome = validator.validate(jsonWithNoReferences, requestWithPage42)
    assertEquals(setOf(EditorialIssue.MISSING_CLAIM), (outcome as EditorialValidationOutcome.Invalid).issues)
}

@Test fun `assigned exercise used only in material is rejected`() {
    val outcome = validator.validate(materialUsesAssignedExercise, requestWithAssignedExercise)
    assertTrue(EditorialIssue.WRONG_DESTINATION in (outcome as EditorialValidationOutcome.Invalid).issues)
}

@Test fun `linked material must retain page and exercise literals`() {
    val outcome = validator.validate(textSaysPage24ButLinksPage42, requestWithPage42)
    assertTrue(EditorialIssue.MISSING_LITERAL in (outcome as EditorialValidationOutcome.Invalid).issues)
}
```

- [ ] **Step 2: Ejecutar las pruebas y verificar que fallen**

Run: `./gradlew testDebugUnitTest --tests '*EditorialReportCodecTest' --tests '*EditorialReportPromptFactoryTest' --tests '*EditorialReportValidatorTest'`

Expected: FAIL por clases inexistentes.

- [ ] **Step 3: Implementar el códec cerrado**

El códec debe exigir exactamente `summary`, `material`, `homework`, `summary_source_claim_ids` y `discarded`. Cada item visible exige `text` y `source_claim_ids`; cada descarte exige `claim_id` y `reason`. Rechazar más de 200 items, textos individuales mayores a 1.000 caracteres y respuesta total mayor a 64 KiB.

```kotlin
object EditorialReportCodec {
    fun decode(rawJson: String): EditorialReport {
        require(rawJson.toByteArray().size <= 65_536) { "Editorial response too large" }
        val root = JSONObject(rawJson)
        requireExactKeys(root, setOf("summary", "material", "homework", "summary_source_claim_ids", "discarded"))
        return EditorialReport(
            summary = root.requireString("summary"),
            material = root.requireOutputItems("material"),
            homework = root.requireOutputItems("homework"),
            summarySourceClaimIds = root.requireStringList("summary_source_claim_ids"),
            discarded = root.requireDiscards("discarded"),
        )
    }
}
```

- [ ] **Step 4: Implementar prompt y esquema**

El prompt debe separar `<accepted_evidence>` de las instrucciones, exigir español rioplatense, preservar números y prohibir que material/tarea usen claims no declarados.

```kotlin
data class EditorialPrompt(val systemInstruction: String, val userText: String, val jsonSchema: String)

class EditorialReportPromptFactory {
    fun create(request: EditorialReportRequest, repair: EditorialRepair? = null): EditorialPrompt
}
```

En reparación, incluir solamente `EditorialIssue.name` e ids faltantes; no reenviar cuerpos HTTP ni credenciales.

- [ ] **Step 5: Implementar el validador**

```kotlin
sealed interface EditorialValidationOutcome {
    data class Valid(val report: EditorialReport) : EditorialValidationOutcome
    data class Invalid(val issues: Set<EditorialIssue>, val missingClaimIds: Set<String>) : EditorialValidationOutcome
}
```

El validador debe construir el conjunto de ids usados, exigir una decisión para cada input, aplicar `StatusFieldPolicy` y comprobar los literales normalizados de `PAGE` y `EXERCISE` solamente dentro del item que los referencia. No debe reordenar ni reescribir el texto.

- [ ] **Step 6: Ejecutar las pruebas específicas**

Run: `./gradlew testDebugUnitTest --tests '*EditorialReportCodecTest' --tests '*EditorialReportPromptFactoryTest' --tests '*EditorialReportValidatorTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/editorial app/src/test/java/com/capo/diarioclase/processing/editorial
git commit -m "feat: validate audited editorial report responses"
```

## Task 3: Adaptadores y router de la segunda etapa

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/EditorialProviderClient.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/GeminiEditorialProviderClient.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/OpenAiEditorialProviderClient.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/EditorialReportRouter.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/editorial/GeminiEditorialProviderClientTest.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/editorial/OpenAiEditorialProviderClientTest.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/editorial/EditorialReportRouterTest.kt`

**Interfaces:**
- Consumes: `InferenceHttpTransport`, `ProviderOutcome`, `ProviderModel`, `EphemeralCredential`, `ProviderRetryPolicy`, `EditorialReportPromptFactory` y `EditorialReportValidator`.
- Produces: `EditorialProviderClient.generate`, `EditorialRoute.Ready` y `EditorialRoute.Unavailable`.

- [ ] **Step 1: Escribir fakes y pruebas fallidas de proveedor**

```kotlin
interface EditorialProviderClient {
    suspend fun generate(
        request: EditorialReportRequest,
        credential: EphemeralCredential,
        repair: EditorialRepair? = null,
    ): ProviderOutcome
}

@Test fun `gemini sends native editorial schema and accepted evidence only`() = runTest {
    client.generate(request, EphemeralCredential("secret"))
    assertTrue(recorded.body.contains("responseJsonSchema"))
    assertTrue(recorded.body.contains("accepted_evidence"))
    assertFalse(recorded.body.contains("session-real-id"))
}
```

- [ ] **Step 2: Ejecutar pruebas y verificar que fallen**

Run: `./gradlew testDebugUnitTest --tests '*EditorialProviderClientTest' --tests '*EditorialReportRouterTest'`

Expected: FAIL por adaptadores inexistentes.

- [ ] **Step 3: Implementar adaptadores editoriales**

Reutilizar `InferenceHttpTransport`, `runTransport`, `mapHttpStatusToFailure`, `stripJsonFences` y los perfiles existentes. Gemini debe usar `responseMimeType=application/json` y esquema nativo. Groq debe usar `json_schema` estricto. OpenRouter debe mantener `json_object`, `data_collection=deny` y precio máximo cero.

- [ ] **Step 4: Implementar router secuencial con una reparación por proveedor**

```kotlin
sealed interface EditorialRoute {
    data class Ready(
        val report: EditorialReport,
        val rawJson: String,
        val provider: InferenceProvider,
        val modelId: String,
    ) : EditorialRoute
    data class Unavailable(val failures: List<ProviderFailure>) : EditorialRoute
}
```

Por proveedor: hacer intento inicial; si el transporte es válido pero el contrato o la cobertura fallan, hacer una reparación con issues e ids faltantes; si vuelve a fallar, continuar al proveedor siguiente. `NO_NETWORK` corta toda la cadena. Envolver la etapa completa con `withTimeoutOrNull(60_000)`.

- [ ] **Step 5: Agregar pruebas del orden y la reparación**

```kotlin
@Test fun `incomplete response is repaired once before next provider`() = runTest {
    val result = router.route(request, listOf(gemini, groq))
    assertIs<EditorialRoute.Ready>(result)
    assertEquals(listOf("GEMINI:initial", "GEMINI:repair"), attempts)
}

@Test fun `failed repair advances to next free provider`() = runTest {
    router.route(request, listOf(gemini, groq))
    assertEquals(listOf("GEMINI:initial", "GEMINI:repair", "GROQ:initial"), attempts)
}
```

- [ ] **Step 6: Ejecutar las pruebas específicas**

Run: `./gradlew testDebugUnitTest --tests '*GeminiEditorialProviderClientTest' --tests '*OpenAiEditorialProviderClientTest' --tests '*EditorialReportRouterTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/editorial app/src/test/java/com/capo/diarioclase/processing/editorial
git commit -m "feat: route second-pass reports through free providers"
```

## Task 4: Room 9 y store editorial transaccional

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/RoomEditorialReportStore.kt`
- Create: `app/schemas/com.capo.diarioclase.data.db.DiarioDatabase/9.json`
- Test: `app/src/test/java/com/capo/diarioclase/processing/editorial/RoomEditorialReportStoreTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/data/db/DiarioMigrationTest.kt`

**Interfaces:**
- Consumes: `EditorialReportState`, raw JSON validado, versiones y `inputHash`.
- Produces: `EditorialReportEntity`, `EditorialReportStore` y `MIGRATION_8_9`.

- [ ] **Step 1: Escribir pruebas fallidas de migración y compare-and-set**

```kotlin
@Test fun `migration 8 to 9 preserves legacy diaries and creates editorial table`() {
    migrateFrom(8, 9)
    assertEquals("tema legacy", queryString("SELECT topics FROM diary_entries"))
    assertEquals(0, queryLong("SELECT COUNT(*) FROM editorial_reports"))
}

@Test fun `late ready result cannot replace stale input hash`() = runTest {
    store.begin("s", "new-hash")
    store.markStale("s")
    assertFalse(store.saveReadyIfCurrent("s", "new-hash", ready))
}
```

- [ ] **Step 2: Ejecutar pruebas y verificar que fallen**

Run: `./gradlew testDebugUnitTest --tests '*DiarioMigrationTest' --tests '*RoomEditorialReportStoreTest'`

Expected: FAIL por esquema y store inexistentes.

- [ ] **Step 3: Agregar entidad temporal editorial**

```kotlin
@Entity(tableName = "editorial_reports", indices = [Index("sessionId", unique = true)])
data class EditorialReportEntity(
    @PrimaryKey val sessionId: String,
    val inputHash: String,
    val state: String,
    val rawJson: String,
    val summary: String,
    val materialText: String,
    val homeworkText: String,
    val provider: String?,
    val modelId: String?,
    val promptVersion: String,
    val schemaVersion: String,
    val validatorVersion: String,
    val failure: String?,
    val updatedAtEpochMs: Long,
)
```

Agregar a `DiaryEntryEntity` los campos permanentes `reportSummary`, `reportMaterial`, `reportHomework`, `reportAuditJson`, `reportProvider`, `reportModelId`, `reportInputHash`, `reportPromptVersion`, `reportSchemaVersion` y `reportValidatorVersion`, todos con defaults compatibles.

- [ ] **Step 4: Implementar DAO y store**

```kotlin
interface EditorialReportStore {
    suspend fun readyFor(sessionId: String, inputHash: String): EditorialReportEntity?
    suspend fun begin(sessionId: String, inputHash: String)
    suspend fun saveReadyIfCurrent(sessionId: String, inputHash: String, ready: EditorialReportEntity): Boolean
    suspend fun markFailedIfCurrent(sessionId: String, inputHash: String, failure: String)
    suspend fun markStale(sessionId: String)
    fun observe(sessionId: String): Flow<EditorialReportEntity?>
}
```

`saveReadyIfCurrent` debe ser un `UPDATE ... WHERE sessionId=:sessionId AND inputHash=:inputHash AND state='GENERATING'` y devolver `rows == 1`.

- [ ] **Step 5: Implementar `MIGRATION_8_9` y registrar la entidad**

Crear `editorial_reports`, sus índices y las columnas aditivas de `diary_entries`. Registrar `MIGRATION_8_9` en `DiarioClaseApp`. Generar el schema 9 mediante KSP.

- [ ] **Step 6: Ejecutar pruebas de migración y store**

Run: `./gradlew testDebugUnitTest --tests '*DiarioMigrationTest' --tests '*RoomEditorialReportStoreTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/data app/src/main/java/com/capo/diarioclase/processing/editorial app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt app/src/test/java/com/capo/diarioclase/data app/src/test/java/com/capo/diarioclase/processing/editorial app/schemas
git commit -m "feat: persist versioned editorial reports in Room 9"
```

## Task 5: Servicio editorial, caché e invalidación

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/editorial/EditorialReportService.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/RoomProcessingStore.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/LocalDraftReprojector.kt`
- Test: `app/src/test/java/com/capo/diarioclase/processing/editorial/EditorialReportServiceTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/work/RoomProcessingStorePersistenceTest.kt`

**Interfaces:**
- Consumes: builder, router y store de Tasks 1–4.
- Produces: `EditorialGenerationOutcome`, `EditorialReportService.generate(sessionId, mode, claims)` e invalidación atómica desde las revisiones.

- [ ] **Step 1: Escribir pruebas fallidas de caché, stale y respuesta tardía**

```kotlin
@Test fun `matching ready report returns without provider call`() = runTest {
    store.seedReady(request.inputHash, report)
    assertIs<EditorialGenerationOutcome.Ready>(service.generate(session, mode, claims))
    assertEquals(0, router.calls)
}

@Test fun `late response is discarded after review marks report stale`() = runTest {
    val deferred = async { service.generate(session, mode, claims) }
    store.markStale(session.value)
    router.complete(validRoute)
    assertIs<EditorialGenerationOutcome.Obsolete>(deferred.await())
}

@Test fun `review and editorial invalidation share one transaction`() = runTest {
    processingStore.reviewClaim(claim.id, ReviewAction.CORRECT, "Ejercicio 4")
    assertEquals("STALE", dao.editorialReport(session.value)?.state)
}
```

- [ ] **Step 2: Ejecutar pruebas y verificar que fallen**

Run: `./gradlew testDebugUnitTest --tests '*EditorialReportServiceTest' --tests '*RoomProcessingStorePersistenceTest'`

Expected: FAIL.

- [ ] **Step 3: Implementar servicio y resultados**

```kotlin
sealed interface EditorialGenerationOutcome {
    data class Ready(val entity: EditorialReportEntity, val cacheHit: Boolean) : EditorialGenerationOutcome
    data class Unavailable(val failures: List<ProviderFailure>) : EditorialGenerationOutcome
    data object Obsolete : EditorialGenerationOutcome
}

class EditorialReportService(
    private val builder: EditorialReportPacketBuilder,
    private val router: EditorialReportRouter,
    private val store: EditorialReportStore,
    private val enabledProviders: suspend () -> List<ProviderModel>,
) {
    suspend fun generate(sessionId: SessionId, mode: InterpretationMode, claims: List<EvidenceClaim>): EditorialGenerationOutcome
}
```

Flujo: construir request; devolver cache READY compatible; `begin`; llamar router; convertir el report a textos sin alterar la prosa; guardar con compare-and-set; devolver `Obsolete` si el hash ya no está vigente.

- [ ] **Step 4: Invalidar dentro de revisión y reproyección**

En `RoomProcessingStore.reviewClaim`, ejecutar `dao.markEditorialReportStale(claim.sessionId)` dentro de la misma transacción que actualiza el claim y reproyecta. Exponer `invalidateEditorialReport(sessionId)` para que `AndroidCaptureActions.reprojectMode` la llame inmediatamente después de cambiar el modo.

- [ ] **Step 5: Ejecutar pruebas específicas**

Run: `./gradlew testDebugUnitTest --tests '*EditorialReportServiceTest' --tests '*RoomProcessingStorePersistenceTest'`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/editorial app/src/main/java/com/capo/diarioclase/processing/work app/src/test/java/com/capo/diarioclase/processing
git commit -m "feat: generate and invalidate editorial reports safely"
```

## Task 6: Integración inicial y regeneración desde la pantalla

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionCoordinator.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureViewModel.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureUiState.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/work/TranscriptionCoordinatorTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/ui/capture/CaptureViewModelTest.kt`

**Interfaces:**
- Consumes: `EditorialReportService` y `EditorialReportStore`.
- Produces: generación automática inicial, `CaptureActions.regenerateEditorialReport`, `EditorialReportUi` y estados visibles.

- [ ] **Step 1: Escribir pruebas fallidas de integración**

```kotlin
@Test fun `completed first pass requests editorial report from accepted claims`() = runTest {
    coordinator.process(sessionId, mode)
    assertEquals(listOf(acceptedPage.id, acceptedHomework.id), editorialService.lastClaimIds)
}

@Test fun `review makes report stale and exposes regenerate action`() = runTest {
    viewModel.acceptClaim(confirmClaim.id)
    advanceUntilIdle()
    assertEquals(EditorialUiState.STALE, viewModel.state.value.editorial?.state)
}

@Test fun `regenerate uses persisted claims without retranscribing audio`() = runTest {
    viewModel.onRegenerateEditorialReport()
    advanceUntilIdle()
    assertEquals(1, actions.editorialCalls)
    assertEquals(0, actions.transcriptionCalls)
}
```

- [ ] **Step 2: Ejecutar pruebas y verificar que fallen**

Run: `./gradlew testDebugUnitTest --tests '*TranscriptionCoordinatorTest' --tests '*CaptureViewModelTest'`

Expected: FAIL.

- [ ] **Step 3: Generar la ficha editorial después de guardar evidencia**

Inyectar `EditorialReportService?` en `TranscriptionCoordinator`. Después de `store.saveEvidence`, llamar `generate(sessionId, mode, claims)` antes de marcar `AWAITING_REVIEW`. Una falla editorial no debe cambiar el éxito de Whisper ni borrar la evidencia.

- [ ] **Step 4: Componer dependencias en `DiarioClaseApp`**

Crear clientes editoriales con el mismo transporte, credenciales y perfiles habilitados. Mantener separado el router de claims del router editorial. Exponer `editorialReportService` y `editorialReportStore`.

- [ ] **Step 5: Agregar acción explícita de regeneración**

```kotlin
interface CaptureActions {
    suspend fun regenerateEditorialReport(id: SessionId, mode: InterpretationMode)
}
```

`AndroidCaptureActions` debe cargar claims persistidos, llamar al servicio editorial y nunca reanudar WorkManager ni Whisper.

- [ ] **Step 6: Exponer estado editorial en ViewModel**

```kotlin
data class EditorialReportUi(
    val state: EditorialReportState,
    val report: EditorialReport?,
    val provider: String?,
    val failure: String?,
)
```

Combinar `observeEditorialReport(sessionId)` con draft y claims. Decodificar `rawJson` validado para
conservar `sourceClaimIds` en cada item; no aplanarlo antes de llegar a la UI. Deshabilitar
aprobación salvo `READY`. Mostrar `STALE` inmediatamente después de una revisión.

- [ ] **Step 7: Ejecutar pruebas específicas**

Run: `./gradlew testDebugUnitTest --tests '*TranscriptionCoordinatorTest' --tests '*CaptureViewModelTest'`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase app/src/test/java/com/capo/diarioclase/processing/work app/src/test/java/com/capo/diarioclase/ui/capture
git commit -m "feat: run editorial pass after accepted evidence"
```

## Task 7: Persistencia permanente y barrera de limpieza

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/diary/DiaryModels.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/repository/RoomDiaryRepository.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/diary/cleanup/CleanupCoordinator.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/diary/DiaryClipboardFormatter.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/data/repository/RoomDiaryRepositoryTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/diary/cleanup/CleanupCoordinatorTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/diary/DiaryClipboardFormatterTest.kt`

**Interfaces:**
- Consumes: `EditorialReportEntity` READY.
- Produces: campos editoriales permanentes en `DiaryEntry`, guardado verificado y copiado final.

- [ ] **Step 1: Escribir pruebas fallidas de aprobación segura**

```kotlin
@Test fun `saveVerified refuses stale editorial report`() = runTest {
    dao.saveEditorialReport(report.copy(state = "STALE"))
    assertIs<DiarySaveResult.Failed>(repository.saveVerified(sessionId, draft))
    assertNull(dao.diaryBySession(sessionId.value))
}

@Test fun `cleanup never starts when editorial report is generating`() = runTest {
    val outcome = coordinator.approveAndClean(sessionId, draft)
    assertIs<CleanupOutcome.SaveFailed>(outcome)
    assertEquals(0, files.deleteCalls)
}

@Test fun `clipboard prefers permanent editorial report`() {
    assertEquals(expectedEditorialText, formatter.format(entryWithEditorialReport))
}
```

- [ ] **Step 2: Ejecutar pruebas y verificar que fallen**

Run: `./gradlew testDebugUnitTest --tests '*RoomDiaryRepositoryTest' --tests '*CleanupCoordinatorTest' --tests '*DiaryClipboardFormatterTest'`

Expected: FAIL.

- [ ] **Step 3: Extender dominio y repositorio**

Agregar a `DiaryEntry` los campos editoriales permanentes con defaults vacíos. En `saveVerified`, dentro de la misma transacción: leer `editorial_reports`; exigir `READY`; copiar textos, raw JSON auditado, proveedor, modelo, hash y versiones; insertar `DiaryEntryEntity`; releer y comparar.

- [ ] **Step 4: Mantener fallback de diarios legacy**

`DiaryClipboardFormatter.format(entry)` debe usar:

```kotlin
if (entry.reportSummary.isNotBlank() || entry.reportMaterial.isNotBlank() || entry.reportHomework.isNotBlank()) {
    formatEditorial(entry.pedagogicalDate, entry.reportSummary, entry.reportMaterial, entry.reportHomework)
} else {
    formatDraft(entry.pedagogicalDate, entry.topics, entry.activities, entry.pages, entry.completedExercises, entry.homework)
}
```

- [ ] **Step 5: Verificar la barrera de limpieza**

No modificar el orden de `CleanupCoordinator`: guardar, releer, comparar, cambiar a APPROVED,
borrar archivos, borrar filas temporales. Después de la copia permanente, agregar
`editorial_reports` al borrado de `RoomTemporaryCleanupStore.deleteSessionTemporaryRows` y a
`temporaryRowCount`; antes de la copia nunca se elimina. Agregar pruebas para `GENERATING`,
`STALE`, `FAILED`, ausencia de reporte y limpieza pendiente con reporte permanente recuperable.

- [ ] **Step 6: Ejecutar pruebas específicas**

Run: `./gradlew testDebugUnitTest --tests '*RoomDiaryRepositoryTest' --tests '*CleanupCoordinatorTest' --tests '*DiaryClipboardFormatterTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/diary app/src/main/java/com/capo/diarioclase/data/repository app/src/test/java/com/capo/diarioclase/diary app/src/test/java/com/capo/diarioclase/data/repository
git commit -m "feat: archive editorial report before temporary cleanup"
```

## Task 8: Interfaz de ficha final y archivo

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureScreen.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/archive/ArchiveViewModel.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/archive/ArchiveScreen.kt`
- Test: `app/src/test/java/com/capo/diarioclase/ui/capture/CaptureScreenLogicTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/ui/archive/ArchiveViewModelTest.kt`

**Interfaces:**
- Consumes: `EditorialReportUi` y campos editoriales de `DiaryEntry`.
- Produces: ficha visible/copiar, estados de error y comparación legacy colapsada.

- [ ] **Step 1: Extraer y probar lógica de presentación**

```kotlin
internal fun canApproveEditorial(state: EditorialReportUi?): Boolean = state?.state == EditorialReportState.READY
internal fun editorialActionLabel(state: EditorialReportState?): String = when (state) {
    EditorialReportState.GENERATING -> "GENERANDO FICHA FINAL"
    EditorialReportState.STALE -> "REGENERAR FICHA FINAL"
    EditorialReportState.FAILED -> "REINTENTAR FICHA FINAL"
    EditorialReportState.READY -> "REGENERAR REDACCIÓN"
    null -> "GENERAR FICHA FINAL"
}
```

Probar que `STALE`, `FAILED` y `GENERATING` bloquean aprobación.

- [ ] **Step 2: Ejecutar pruebas y verificar que fallen**

Run: `./gradlew testDebugUnitTest --tests '*CaptureScreenLogicTest' --tests '*ArchiveViewModelTest'`

Expected: FAIL.

- [ ] **Step 3: Rehacer la sección principal de `DraftScreen`**

Mostrar, en este orden:

1. `RESUMEN`;
2. `MATERIAL TRABAJADO`;
3. `TAREA`;
4. acción copiar;
5. estado/proveedor y acción regenerar;
6. evidencia aceptada y por confirmar;
7. sección cerrada `COMPARAR CON FICHA ANTERIOR`.

Cada item de material o tarea debe poder expandirse y mostrar los claims de `sourceClaimIds` con su
valor y excerpt, resueltos desde `state.claims`. El resumen ofrece la misma inspección con
`summarySourceClaimIds`. No permitir edición libre del texto editorial en esta prueba. Las
correcciones se hacen sobre claims. Si el estado es `FAILED`, la vista de respaldo es la sección de
evidencia aceptada completa y debe rotularse como respaldo, nunca como ficha final.

- [ ] **Step 4: Actualizar confirmación de aprobación**

El diálogo debe decir que se conservará la ficha final y se eliminarán audio, transcripción y evidencia. El botón `APROBAR` solo se habilita con reporte `READY`.

- [ ] **Step 5: Actualizar archivo y búsqueda**

Los diarios nuevos muestran las tres secciones editoriales. Los legacy mantienen los cinco campos. La búsqueda debe incluir `reportSummary`, `reportMaterial` y `reportHomework`. La edición legacy permanece; para diarios editoriales la prueba permite editar solamente fecha y nivel, no el informe auditado.

- [ ] **Step 6: Ejecutar pruebas específicas**

Run: `./gradlew testDebugUnitTest --tests '*CaptureScreenLogicTest' --tests '*ArchiveViewModelTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/ui app/src/test/java/com/capo/diarioclase/ui
git commit -m "feat: present editorial summary material and homework"
```

## Task 9: Recorrido completo, release experimental y protocolo físico

**Files:**
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/evaluation/SemanticIntegrityEvaluationTest.kt`
- Modify: `app/build.gradle.kts`
- Create: `SECOND_PASS_REPORT_DEVICE_TEST.md`
- Modify: `PHASE5_HANDOFF.md`

**Interfaces:**
- Consumes: sistema completo de Tasks 1–8.
- Produces: versión `0.8.0-editorial-pass`, APK debug y protocolo reproducible.

- [ ] **Step 1: Escribir el recorrido fallido de extremo a extremo**

El test debe simular: claims aceptados, respuesta editorial válida, revisión que vuelve STALE, regeneración, aprobación, copia permanente, limpieza y reapertura sin red.

```kotlin
@Test fun `accepted evidence becomes permanent editorial report before cleanup`() = runTest {
    processSession()
    assertEquals("READY", dao.editorialReport(sessionId)?.state)
    reviewExercise("3", "4")
    assertEquals("STALE", dao.editorialReport(sessionId)?.state)
    regenerateEditorialReport()
    val outcome = approveAndClean()
    assertIs<CleanupOutcome.Archived>(outcome)
    assertEquals("Página 42, ejercicio 4.", dao.diaryBySession(sessionId)!!.reportMaterial)
    assertEquals(0, dao.temporaryRowCount(sessionId))
    assertEquals(0, fakeEditorialClient.callsAfterReopen)
}
```

- [ ] **Step 2: Ejecutar recorrido y verificar que falle antes del cableado final**

Run: `./gradlew testDebugUnitTest --tests '*FullJourneyTest'`

Expected: FAIL en la expectativa editorial todavía no cubierta por el fixture completo.

- [ ] **Step 3: Completar fixtures de evaluación**

Agregar escenarios para páginas intercaladas, rangos, autocorrección, realizado/asignado, tarea indirecta, duplicados, ruido y respuesta tardía. El reporte debe separar fallas de primera interpretación de fallas editoriales.

- [ ] **Step 4: Actualizar versión**

En `app/build.gradle.kts`:

```kotlin
versionCode = 15
versionName = "0.8.0-editorial-pass"
```

- [ ] **Step 5: Escribir protocolo del Moto g max**

`SECOND_PASS_REPORT_DEVICE_TEST.md` debe incluir tres grabaciones reales, prueba sin red, corrección y regeneración, cierre/reapertura, comparación contra ficha anterior, aprobación/limpieza y tabla de omisiones, repeticiones, números alterados y asociaciones incorrectas.

- [ ] **Step 6: Ejecutar verificación completa**

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Expected: BUILD SUCCESSFUL; tests, lint, APK y APK de instrumentación generados sin red real ni claves.

- [ ] **Step 7: Escanear secretos y revisar el APK**

Run:

```bash
rg -n "AIza|gsk_|sk-or-|Bearer [A-Za-z0-9]" app/src docs SECOND_PASS_REPORT_DEVICE_TEST.md
find app/build/outputs/apk -type f -name '*.apk' -print
```

Expected: sin secretos reales; APK debug `0.8.0-editorial-pass` presente.

- [ ] **Step 8: Actualizar handoff con resultados exactos**

Registrar base SHA, head SHA, comandos, cantidad de tests, estado de lint/build, schema 9, riesgos y prueba física pendiente. No declarar la versión final hasta completar el protocolo en el Moto g max.

- [ ] **Step 9: Commit**

```bash
git add app/src/test app/build.gradle.kts SECOND_PASS_REPORT_DEVICE_TEST.md PHASE5_HANDOFF.md
git commit -m "test: validate editorial-pass release candidate"
```

## Verificación final de la rama

- [ ] Confirmar que el diff no modifica Whisper, ventanas, deduplicación ni envío de audio.
- [ ] Confirmar que `editorial_reports` se invalida en revisión y cambio de modo.
- [ ] Confirmar que ninguna ruta de aprobación acepta estados distintos de `READY`.
- [ ] Confirmar que el archivo permanente contiene informe y auditoría antes del borrado.
- [ ] Confirmar que diarios v8 legacy abren, se copian y se buscan.
- [ ] Confirmar que Gemini, Groq y OpenRouter usan solamente modelos del catálogo gratuito.
- [ ] Confirmar que el cliente muestra literalmente la prosa validada y no usa `PagesAndExercisesComposer` para la ficha final.

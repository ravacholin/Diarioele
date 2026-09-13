# Whisper local en español: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reemplazar `SpeechRecognizer` por Whisper `base` local, bloqueado a español, con procesamiento persistente por ventanas recuperables y sin cambiar las garantías de conservación y limpieza de fase 3.

**Architecture:** El APK incorpora el modelo verificado y una compilación ARM64 de `whisper.cpp`. Un adaptador JNI transcribe ventanas PCM; Room confirma cada ventana; un `CoroutineWorker` único por sesión coordina reanudación, progreso y extracción final. La UI observa Room y solicita iniciar, pausar o retomar, sin ejecutar inferencia dentro del `ViewModel`.

**Tech Stack:** Kotlin 2.1.20, Android SDK 35, Jetpack Compose, Room 2.7.1, WorkManager, CMake/NDK, JNI, `whisper.cpp` v1.9.4.

**Spec:** `PHASE4_WHISPER_LOCAL_DESIGN.md`

## Global Constraints

- Rama de trabajo y destino: `main`.
- Android solamente; `minSdk = 26`, `targetSdk = 35`; dispositivo de aceptación: Moto g max con Android 16.
- Mantener `applicationId = "com.capo.diarioclase.phase2"` para conservar la identidad de la aplicación.
- No agregar `android.permission.INTERNET`, cuentas, nube, backend ni API paga.
- Modelo: `ggml-base.bin`, revisión Hugging Face `5359861c739e955e79d9a303bcbc70fb988958b1`.
- Modelo URL: `https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin`.
- Modelo tamaño exacto: `147951465` bytes; SHA-256: `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`.
- Motor: `whisper.cpp` v1.9.4, commit `927cfce34f31707e17f2bff35c349632fb9e2c3a`.
- ABI inicial única: `arm64-v8a`.
- Idioma fijo `es`; tarea fija `transcribe`; detección automática y traducción desactivadas.
- Ventanas de 30.000 ms con solapamiento de 2.000 ms; timeout de 300.000 ms por ventana.
- Ningún fallo, pausa, cancelación o cierre borra audio ni resultados parciales.
- La limpieza conserva el orden de fase 3: guardar ficha permanente, releer, comparar, borrar temporales, verificar cero temporales.
- Los modos Conservador, Equilibrado y Exhaustivo solo afectan la proyección de evidencia.
- Mantener el estilo oscuro, monocromo, rectangular y sin emoji.
- Los Markdown del proyecto permanecen en la raíz; no crear `docs`.
- La firma estable del APK no forma parte de esta implementación.

## Mapa de archivos

- `scripts/prepare-whisper-model.sh`: descarga o valida el modelo exacto antes de compilar.
- `app/src/main/cpp/vendor/whisper.cpp/`: copia fijada del código oficial y su licencia.
- `app/src/main/cpp/CMakeLists.txt`: compila el motor y el puente JNI ARM64.
- `app/src/main/cpp/WhisperJni.cpp`: frontera JNI, configuración española y cancelación.
- `processing/transcription/WhisperNativeBridge.kt`: contrato Kotlin del motor nativo.
- `processing/transcription/WhisperModelInstaller.kt`: valida y copia atómicamente el asset al almacenamiento interno no respaldable.
- `processing/transcription/AudioWindowing.kt`: planifica y lee ventanas WAV sin duplicar el archivo.
- `processing/transcription/TranscriptDeduplicator.kt`: elimina texto del solapamiento mediante tiempos absolutos.
- `processing/transcription/WhisperTranscriptionEngine.kt`: carga el modelo y convierte resultados nativos a `TranscriptSpan`.
- `data/db/Entities.kt`, `SessionDao.kt`, `DiarioDatabase.kt`: ejecución y checkpoints persistentes, migración 3→4.
- `processing/work/RoomProcessingStore.kt`: transacciones de checkpoint, spans y estados.
- `processing/work/TranscriptionCoordinator.kt`: ejecuta una ventana, confirma avance y genera la ficha al terminar.
- `processing/work/TranscriptionWorker.kt`: ciclo persistente, foreground info, pausa, reintento y timeout.
- `processing/work/TranscriptionWorkScheduler.kt`: trabajo único por sesión y comandos de pausa/reanudación.
- `ui/capture/CaptureUiState.kt`, `CaptureViewModel.kt`, `CaptureScreen.kt`: progreso observable y controles.
- `diary/cleanup/CleanupCoordinator.kt`: incluye checkpoints y ejecución temporal en la limpieza verificada.
- `DiarioClaseApp.kt`, `AndroidCaptureActions.kt`, `MainActivity.kt`, `AndroidManifest.xml`: composición final y eliminación del flujo del modelo de Android.

---

### Task 1: Cadena verificable del modelo incluido

**Files:**
- Create: `scripts/prepare-whisper-model.sh`
- Modify: `.gitignore`
- Modify: `.github/workflows/android-apk.yml`
- Modify: `app/build.gradle.kts`
- Test: `scripts/prepare-whisper-model.sh`

**Interfaces:**
- Consumes: URL, tamaño y SHA-256 fijados en Global Constraints.
- Produces: `app/src/main/assets/models/ggml-base.bin` validado antes de `preBuild`.

- [ ] **Step 1: Escribir el script con modo de validación comprobable**

```bash
#!/usr/bin/env bash
set -euo pipefail
readonly model_url="https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin"
readonly expected_size="147951465"
readonly expected_sha="60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
readonly output="${1:-app/src/main/assets/models/ggml-base.bin}"
mkdir -p "$(dirname "$output")"

if [[ ! -f "$output" ]]; then
  curl --fail --location --retry 5 --retry-all-errors "$model_url" --output "$output.part"
  mv "$output.part" "$output"
fi

[[ "$(wc -c < "$output" | tr -d ' ')" == "$expected_size" ]]
echo "$expected_sha  $output" | sha256sum --check --status
```

- [ ] **Step 2: Verificar que un archivo corrupto sea rechazado**

Run: `tmp_model=$(mktemp); printf 'incorrecto' > "$tmp_model"; if scripts/prepare-whisper-model.sh "$tmp_model"; then exit 1; fi`

Expected: exit `0` del comando envolvente porque el script interno rechaza tamaño o SHA.

- [ ] **Step 3: Excluir el binario generado y conservar su carpeta**

```gitignore
app/src/main/assets/models/*.bin
!app/src/main/assets/models/.gitkeep
```

- [ ] **Step 4: Integrar preparación y empaquetado**

En `app/build.gradle.kts`:

```kotlin
android {
    androidResources { noCompress += "bin" }
}

val prepareWhisperModel by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("bash", "scripts/prepare-whisper-model.sh")
}
tasks.named("preBuild").configure { dependsOn(prepareWhisperModel) }
```

En el workflow, antes de las pruebas que requieren empaquetado:

```yaml
      - name: Preparar modelo Whisper verificado
        run: bash scripts/prepare-whisper-model.sh
```

- [ ] **Step 5: Descargar y verificar el modelo real una vez**

Run: `bash scripts/prepare-whisper-model.sh && sha256sum app/src/main/assets/models/ggml-base.bin`

Expected: `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`.

- [ ] **Step 6: Commit**

```bash
git add scripts/prepare-whisper-model.sh .gitignore .github/workflows/android-apk.yml app/build.gradle.kts app/src/main/assets/models/.gitkeep
git commit -m "build: verify bundled Whisper base model"
```

### Task 2: Ventanas WAV y deduplicación

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/transcription/AudioWindowing.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/transcription/TranscriptDeduplicator.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/transcription/AudioWindowingTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/transcription/TranscriptDeduplicatorTest.kt`

**Interfaces:**
- Consumes: WAV PCM16 mono 16 kHz creado por `FileSegmentStore`.
- Produces: `AudioWindowPlan`, `PcmWindowReader.read(file: File, plan: AudioWindowPlan): FloatArray`, `TranscriptDeduplicator.merge(existing: List<TranscriptSpan>, incoming: List<TranscriptSpan>): List<TranscriptSpan>`.

- [ ] **Step 1: Escribir pruebas fallidas del planificador**

```kotlin
@Test fun `a 65 second segment creates three windows with two second overlap`() {
    assertEquals(
        listOf(
            AudioWindowPlan(0, 0, 30_000, 28_000),
            AudioWindowPlan(1, 28_000, 58_000, 56_000),
            AudioWindowPlan(2, 56_000, 65_000, 65_000),
        ),
        AudioWindowPlanner.plan(65_000),
    )
}

@Test fun `empty duration has no windows`() {
    assertTrue(AudioWindowPlanner.plan(0).isEmpty())
}
```

- [ ] **Step 2: Ejecutar para confirmar rojo**

Run: `./gradlew testDebugUnitTest --tests '*AudioWindowingTest'`

Expected: FAIL porque `AudioWindowPlanner` todavía no existe.

- [ ] **Step 3: Implementar tipos y cálculo mínimo**

```kotlin
data class AudioWindowPlan(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val confirmedUntilMs: Long,
)

object AudioWindowPlanner {
    const val WINDOW_MS = 30_000L
    const val OVERLAP_MS = 2_000L

    fun plan(durationMs: Long): List<AudioWindowPlan> {
        if (durationMs <= 0) return emptyList()
        val result = mutableListOf<AudioWindowPlan>()
        var start = 0L
        var index = 0
        while (start < durationMs) {
            val end = minOf(start + WINDOW_MS, durationMs)
            val confirmed = if (end == durationMs) end else end - OVERLAP_MS
            result += AudioWindowPlan(index++, start, end, confirmed)
            start = confirmed
        }
        return result
    }
}
```

- [ ] **Step 4: Probar lectura exacta del WAV**

Crear un WAV temporal mediante `WavHeader`, escribir 32.000 muestras ascendentes y comprobar que `PcmWindowReader.read(file, plan)` devuelve 16.000 valores normalizados para un segundo, empezando en el offset solicitado. Ejecutar: `./gradlew testDebugUnitTest --tests '*AudioWindowingTest'`.

```kotlin
@Test fun `reader returns exactly the requested PCM range`() {
    val file = temporaryWav(sampleRate = 16_000, samples = ShortArray(32_000) { it.toShort() })
    val samples = PcmWindowReader.read(file, AudioWindowPlan(0, 1_000, 2_000, 2_000))
    assertEquals(16_000, samples.size)
    assertEquals(16_000f / 32_768f, samples.first(), 0.0001f)
}
```

- [ ] **Step 5: Probar e implementar deduplicación temporal**

```kotlin
@Test fun `overlap keeps only spans after confirmed boundary`() {
    val prior = listOf(span("a", 0, 30_000, "página doce"))
    val next = listOf(
        span("b", 28_000, 30_000, "página doce"),
        span("c", 30_000, 35_000, "ejercicio tres"),
    )
    assertEquals(listOf("página doce", "ejercicio tres"), TranscriptDeduplicator.merge(prior, next).map { it.text })
}
```

La implementación conserva spans nuevos cuyo `endMs` supera el último `endMs` persistido y descarta repeticiones normalizadas totalmente contenidas en el solapamiento.

- [ ] **Step 6: Ejecutar las pruebas del componente**

Run: `./gradlew testDebugUnitTest --tests '*AudioWindowingTest' --tests '*TranscriptDeduplicatorTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/transcription/AudioWindowing.kt app/src/main/java/com/capo/diarioclase/processing/transcription/TranscriptDeduplicator.kt app/src/test/java/com/capo/diarioclase/processing/transcription
git commit -m "feat: split recordings into recoverable windows"
```

### Task 3: Checkpoints y migración Room 3→4

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/data/db/DiarioMigrationTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/data/db/TranscriptionCheckpointDaoTest.kt`

**Interfaces:**
- Consumes: `SessionEntity`, `AudioSegmentEntity`, ventanas confirmadas.
- Produces: `TranscriptionRunEntity`, `TranscriptionCheckpointEntity` y flows observables.

- [ ] **Step 1: Escribir prueba de migración fallida**

Agregar una base v3 con sesión y diario existentes, migrar a v4 y afirmar que los datos previos siguen presentes y existen las tablas nuevas.

```kotlin
assertEquals(1, queryCount(db, "sessions"))
assertEquals(1, queryCount(db, "diary_entries"))
assertEquals(0, queryCount(db, "transcription_runs"))
assertEquals(0, queryCount(db, "transcription_checkpoints"))
```

- [ ] **Step 2: Ejecutar para confirmar rojo**

Run: `./gradlew testDebugUnitTest --tests '*DiarioMigrationTest'`

Expected: FAIL porque `MIGRATION_3_4` y las tablas no existen.


- [ ] **Step 3: Agregar entidades exactas**

```kotlin
@Entity(tableName = "transcription_runs", foreignKeys = [ForeignKey(
    entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE,
)])
data class TranscriptionRunEntity(
    @PrimaryKey val sessionId: String,
    val state: String,
    val pauseRequested: Boolean,
    val processedMs: Long,
    val totalMs: Long,
    val currentSegmentId: String?,
    val failure: String?,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "transcription_checkpoints",
    primaryKeys = ["audioSegmentId"],
    foreignKeys = [ForeignKey(entity = AudioSegmentEntity::class, parentColumns = ["id"], childColumns = ["audioSegmentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class TranscriptionCheckpointEntity(
    val audioSegmentId: String,
    val sessionId: String,
    val confirmedUntilMs: Long,
    val totalMs: Long,
    val processedWindows: Int,
    val totalWindows: Int,
    val state: String,
    val failure: String?,
    val updatedAtEpochMs: Long,
)
```

- [ ] **Step 4: Implementar `MIGRATION_3_4` y registrar versión 4**

Crear ambas tablas con tipos Room exactos, claves foráneas e índice `index_transcription_checkpoints_sessionId`; registrar `MIGRATION_3_4` en `DiarioClaseApp`.

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS transcription_runs (sessionId TEXT NOT NULL PRIMARY KEY,state TEXT NOT NULL,pauseRequested INTEGER NOT NULL,processedMs INTEGER NOT NULL,totalMs INTEGER NOT NULL,currentSegmentId TEXT,failure TEXT,updatedAtEpochMs INTEGER NOT NULL,FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transcription_checkpoints (audioSegmentId TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,confirmedUntilMs INTEGER NOT NULL,totalMs INTEGER NOT NULL,processedWindows INTEGER NOT NULL,totalWindows INTEGER NOT NULL,state TEXT NOT NULL,failure TEXT,updatedAtEpochMs INTEGER NOT NULL,FOREIGN KEY(audioSegmentId) REFERENCES audio_segments(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcription_checkpoints_sessionId ON transcription_checkpoints(sessionId)")
    }
}
```

- [ ] **Step 5: Agregar DAO y pruebas transaccionales**

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun saveTranscriptionRun(run: TranscriptionRunEntity)

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun saveCheckpoint(checkpoint: TranscriptionCheckpointEntity)

@Query("SELECT * FROM transcription_runs WHERE sessionId=:sessionId")
fun observeTranscriptionRun(sessionId: String): Flow<TranscriptionRunEntity?>

@Query("SELECT * FROM transcription_checkpoints WHERE sessionId=:sessionId ORDER BY audioSegmentId")
suspend fun checkpoints(sessionId: String): List<TranscriptionCheckpointEntity>

@Query("UPDATE transcription_runs SET pauseRequested=1 WHERE sessionId=:sessionId")
suspend fun requestTranscriptionPause(sessionId: String): Int
```

Probar que guardar dos veces el mismo `audioSegmentId` actualiza el límite sin duplicar filas y que la señal de pausa persiste.

- [ ] **Step 6: Ejecutar migración y DAO**

Run: `./gradlew testDebugUnitTest --tests '*DiarioMigrationTest' --tests '*TranscriptionCheckpointDaoTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/data/db app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt app/src/test/java/com/capo/diarioclase/data/db
git commit -m "feat: persist recoverable transcription checkpoints"
```

### Task 4: Motor nativo Whisper bloqueado a español

**Files:**
- Create: `app/src/main/cpp/vendor/whisper.cpp/`
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/WhisperJni.cpp`
- Create: `app/src/main/java/com/capo/diarioclase/processing/transcription/WhisperNativeBridge.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/transcription/WhisperModelInstaller.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/transcription/WhisperTranscriptionEngine.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/transcription/TranscriptionEngine.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/transcription/WhisperTranscriptionEngineTest.kt`
- Create: `app/src/androidTest/java/com/capo/diarioclase/processing/transcription/WhisperNativeSmokeTest.kt`

**Interfaces:**
- Consumes: `AudioWindow` PCM float, asset validado, `WhisperNativeRuntime`.
- Produces: `suspend fun transcribe(window: AudioWindow): WindowTranscriptResult`.

- [ ] **Step 1: Incorporar fuente oficial fijada**

```bash
vendor_tmp=$(mktemp -d)
git clone --filter=blob:none https://github.com/ggml-org/whisper.cpp.git "$vendor_tmp/whisper.cpp"
git -C "$vendor_tmp/whisper.cpp" checkout --detach 927cfce34f31707e17f2bff35c349632fb9e2c3a
test "$(git -C "$vendor_tmp/whisper.cpp" rev-parse HEAD)" = "927cfce34f31707e17f2bff35c349632fb9e2c3a"
mkdir -p app/src/main/cpp/vendor
rsync -a --delete --exclude='.git' --exclude='examples' --exclude='samples' --exclude='tests' "$vendor_tmp/whisper.cpp/" app/src/main/cpp/vendor/whisper.cpp/
```

Conservar `LICENSE` y agregar `UPSTREAM_COMMIT` con el SHA completo.

- [ ] **Step 2: Escribir prueba unitaria fallida del contrato**

```kotlin
@Test fun `engine always requests Spanish transcription`() = runTest {
    val native = FakeWhisperRuntime(result = NativeWindowResult(emptyList()))
    WhisperTranscriptionEngine(fakeInstaller, native).transcribe(window())
    assertEquals("es", native.lastOptions?.language)
    assertFalse(native.lastOptions!!.translate)
    assertFalse(native.lastOptions!!.detectLanguage)
    assertTrue(native.lastOptions!!.prompt.contains("vos"))
}
```

- [ ] **Step 3: Definir contrato Kotlin**

```kotlin
data class WhisperOptions(
    val language: String = "es",
    val translate: Boolean = false,
    val detectLanguage: Boolean = false,
    val prompt: String = RioplatensePrompt.TEXT,
    val threads: Int,
)

data class NativeSpan(val startMs: Long, val endMs: Long, val text: String, val confidence: Double)
data class NativeWindowResult(val spans: List<NativeSpan>)

enum class TranscriptionFailure {
    ON_DEVICE_UNAVAILABLE,
    LANGUAGE_UNAVAILABLE,
    AUDIO_SOURCE_UNSUPPORTED,
    RECOGNIZER_BUSY,
    MODEL_MISSING,
    MODEL_INVALID,
    NATIVE_UNAVAILABLE,
    INVALID_AUDIO,
    OUT_OF_MEMORY,
    NO_SPEECH,
    TIMEOUT,
    INTERRUPTED,
    INTERNAL,
    UNKNOWN,
}

interface WhisperNativeRuntime : AutoCloseable {
    fun load(modelPath: String)
    fun transcribe(samples: FloatArray, options: WhisperOptions): NativeWindowResult
    fun cancel()
}
```

Mantener temporalmente los cuatro códigos específicos de `SpeechRecognizer` para que este commit conserve compatibilidad con la UI existente; Task 7 los elimina junto con ese flujo. Mapear excepción de carga ausente a `MODEL_MISSING`, huella/tamaño incorrectos a `MODEL_INVALID`, `UnsatisfiedLinkError` a `NATIVE_UNAVAILABLE`, encabezado o muestras inválidas a `INVALID_AUDIO`, `OutOfMemoryError` a `OUT_OF_MEMORY`, timeout a `TIMEOUT`, detención del worker a `INTERRUPTED` y cualquier retorno nativo no reconocido a `INTERNAL`.

Nombrar la constante real `RioplatensePrompt.TEXT` y usar exactamente ese nombre en producción y pruebas.

- [ ] **Step 4: Implementar instalador atómico y verificado**

Copiar `assets/models/ggml-base.bin` a `noBackupFilesDir/models/ggml-base.bin.part`, verificar tamaño y SHA-256 durante la copia, hacer `renameTo` al nombre final y rechazar cualquier archivo previo que no coincida. Nunca descargar desde la app.

- [ ] **Step 5: Compilar JNI ARM64**

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(diarioclase_whisper)
set(BUILD_SHARED_LIBS OFF)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/whisper.cpp)
add_library(diarioclase_whisper SHARED WhisperJni.cpp)
target_link_libraries(diarioclase_whisper PRIVATE whisper android log)
```

En `WhisperJni.cpp`, inicializar con `whisper_init_from_file_with_params`, usar `whisper_full_default_params(WHISPER_SAMPLING_GREEDY)`, fijar `language = "es"`, `translate = false`, `detect_language = false`, `initial_prompt`, `no_context = true` y devolver spans con tiempos relativos. El callback de aborto consulta un `std::atomic_bool` activado por `cancel()`.

- [ ] **Step 6: Integrar CMake y ABI en Gradle**

```kotlin
defaultConfig {
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-O3") } }
}
externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
```

- [ ] **Step 7: Ejecutar rojo, verde y smoke test**

Run: `./gradlew testDebugUnitTest --tests '*WhisperTranscriptionEngineTest'`

Expected: PASS después de implementar el adaptador.

Run: `./gradlew assembleDebugAndroidTest`

Expected: compila `libdiarioclase_whisper.so`; `WhisperNativeSmokeTest` carga el modelo, procesa PCM silencioso válido y devuelve sin crash dentro del timeout instrumental.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/cpp app/src/main/java/com/capo/diarioclase/processing/transcription app/src/test/java/com/capo/diarioclase/processing/transcription app/src/androidTest/java/com/capo/diarioclase/processing/transcription app/build.gradle.kts
git commit -m "feat: add offline Spanish Whisper engine"
```

### Task 5: Coordinador reanudable por ventana

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionCoordinator.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/RoomProcessingStore.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/transcription/TranscriptionEngine.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/work/TranscriptionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `WindowTranscriptionEngine`, `AudioWindowPlanner`, checkpoints y spans.
- Produces: `suspend fun processNext(sessionId, mode): ProcessingStepOutcome`.

- [ ] **Step 1: Escribir pruebas fallidas de recuperación**

```kotlin
@Test fun `confirmed window is not transcribed twice after restart`() = runTest {
    store.checkpoint = checkpoint(confirmedUntilMs = 28_000)
    val outcome = coordinator.processNext(sessionId, InterpretationMode.CONSERVATIVE)
    assertEquals(28_000, engine.received.single().startMs)
    assertIs<ProcessingStepOutcome.WindowSaved>(outcome)
}

@Test fun `window failure preserves prior transcript and checkpoint`() = runTest {
    engine.next = WindowTranscriptResult.Failure(TranscriptionFailure.TIMEOUT, true)
    coordinator.processNext(sessionId, InterpretationMode.CONSERVATIVE)
    assertEquals(priorSpans, store.persistedSpans)
    assertEquals(28_000, store.checkpoint!!.confirmedUntilMs)
}
```

- [ ] **Step 2: Ejecutar para confirmar rojo**

Run: `./gradlew testDebugUnitTest --tests '*TranscriptionCoordinatorTest'`

Expected: FAIL porque el coordinador todavía procesa segmentos completos.

- [ ] **Step 3: Definir resultado de un paso**

```kotlin
sealed interface ProcessingStepOutcome {
    data class WindowSaved(val progress: TranscriptionProgress) : ProcessingStepOutcome
    data class Complete(val draft: DiaryDraft) : ProcessingStepOutcome
    data class Paused(val progress: TranscriptionProgress) : ProcessingStepOutcome
    data class Failed(val segmentId: SegmentId, val failure: TranscriptionFailure, val retryable: Boolean) : ProcessingStepOutcome
}

enum class TranscriptionRunState { PREPARING, PROCESSING, PAUSED, COMPLETED, FAILED }

data class TranscriptionProgress(
    val sessionId: SessionId,
    val processedMs: Long,
    val totalMs: Long,
    val currentBlock: Int,
    val currentWindow: Int,
    val totalWindows: Int,
    val state: TranscriptionRunState,
    val failure: TranscriptionFailure?,
)
```

- [ ] **Step 4: Implementar transacción de confirmación**

`RoomProcessingStore.confirmWindow(segmentId: SegmentId, spans: List<TranscriptSpan>, checkpoint: TranscriptionCheckpointEntity, run: TranscriptionRunEntity)` debe insertar solo spans deduplicados, actualizar el checkpoint, recalcular `run.processedMs` y confirmar todo dentro de `database.withTransaction`. Solo al confirmar la última ventana marca el segmento `TRANSCRIBED`.

- [ ] **Step 5: Mantener extracción existente al final**

Cuando todos los segmentos estén `TRANSCRIBED`, reutilizar sin cambios semánticos `LiteralClaimExtractor`, `ClaimReducer` e `InterpretationProjector`; preservar campos de `DiaryDraftEntity.userEdited` y pasar a `AWAITING_REVIEW`.

- [ ] **Step 6: Ejecutar pruebas del coordinador**

Run: `./gradlew testDebugUnitTest --tests '*TranscriptionCoordinatorTest' --tests '*InterpretationProjectorTest' --tests '*LiteralClaimExtractorTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing app/src/test/java/com/capo/diarioclase/processing
git commit -m "feat: resume transcription from confirmed windows"
```

### Task 6: Trabajo persistente, pausa y progreso

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionWorker.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionWorkScheduler.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/work/TranscriptionWorkSchedulerTest.kt`
- Create: `app/src/androidTest/java/com/capo/diarioclase/processing/work/TranscriptionWorkerTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `TranscriptionCoordinator.processNext`, DAO de ejecución y `InterpretationMode`.
- Produces: trabajo único `transcription-<sessionId>` y `Flow<TranscriptionProgress?>`.

- [ ] **Step 1: Agregar WorkManager y TestDriver**

```toml
work = "2.10.1"
androidx-work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
```

- [ ] **Step 2: Escribir prueba fallida de trabajo único**

```kotlin
@Test fun `resume replaces only the same session work`() {
    scheduler.start(sessionId, InterpretationMode.CONSERVATIVE)
    scheduler.resume(sessionId, InterpretationMode.CONSERVATIVE)
    assertEquals("transcription-${sessionId.value}", scheduler.lastUniqueName)
    assertEquals(ExistingWorkPolicy.REPLACE, scheduler.lastPolicy)
}
```

- [ ] **Step 3: Implementar scheduler**

```kotlin
interface TranscriptionScheduler {
    fun start(sessionId: SessionId, mode: InterpretationMode)
    suspend fun pause(sessionId: SessionId)
    fun resume(sessionId: SessionId, mode: InterpretationMode)
}
```

`pause` persiste `pauseRequested = true`; `start` y `resume` limpian esa señal antes de encolar un `OneTimeWorkRequest<TranscriptionWorker>` único con `ExistingWorkPolicy.REPLACE`.

- [ ] **Step 4: Implementar `CoroutineWorker` con timeout**

El `doWork()` llama `setForeground(createForegroundInfo(progress))`, repite `processNext`, comprueba pausa entre ventanas y envuelve cada paso en `withTimeout(300_000)`. Mapea pausa a `Result.success()`, fallo recuperable a `Result.failure()` con código persistido y final completo a `Result.success()`. `onStopped()` invoca `WhisperNativeRuntime.cancel()` y no elimina filas.

- [ ] **Step 5: Declarar notificación de procesamiento**

Agregar canal `diarioclase_transcription`, título `TRANSCRIBIENDO EN ESPAÑOL`, porcentaje determinado y acción `PAUSAR`. No declarar tipo `microphone` para este worker porque procesa archivos y no accede al micrófono. Para API 34+, declarar `specialUse` y su permiso específico:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="specialUse"
    android:exported="false"
    tools:node="merge">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="local_whisper_transcription" />
</service>
```

Agregar `xmlns:tools="http://schemas.android.com/tools"` al elemento `manifest` y pasar `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` al `ForegroundInfo` desde API 34.

- [ ] **Step 6: Probar interrupción y reanudación instrumental**

Con `WorkManagerTestInitHelper`, ejecutar una ventana falsa, detener el worker, crear otro y comprobar que empieza en `confirmedUntilMs` y que el audio sigue existiendo.

Run: `./gradlew testDebugUnitTest --tests '*TranscriptionWorkSchedulerTest' assembleDebugAndroidTest`

Expected: PASS y APK de pruebas compilado.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/capo/diarioclase/processing/work app/src/test/java/com/capo/diarioclase/processing/work app/src/androidTest/java/com/capo/diarioclase/processing/work
git commit -m "feat: run transcription as persistent resumable work"
```

### Task 7: UI observable sin descarga de idioma

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureUiState.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureViewModel.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureScreen.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/MainActivity.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/ui/capture/CaptureViewModelTest.kt`

**Interfaces:**
- Consumes: `Flow<TranscriptionProgress?>`, `TranscriptionScheduler`.
- Produces: estados visibles y comandos `onProcess`, `onPauseTranscription`, `onResumeTranscription`.

- [ ] **Step 1: Escribir pruebas fallidas de estado**

```kotlin
@Test fun `processing state exposes persisted progress`() = runTest {
    progress.value = TranscriptionProgress(sessionId, 56_000, 200_000, 2, 3, 8, TranscriptionRunState.PROCESSING, null)
    advanceUntilIdle()
    assertEquals(28, viewModel.state.value.progressPercent)
    assertEquals("BLOQUE 2 · TRAMO 3 DE 8", viewModel.state.value.progressLabel)
}

@Test fun `pause never clears recording report`() = runTest {
    viewModel.onPauseTranscription()
    advanceUntilIdle()
    assertNotNull(viewModel.state.value.lastRecording)
}
```

- [ ] **Step 2: Ejecutar para confirmar rojo**

Run: `./gradlew testDebugUnitTest --tests '*CaptureViewModelTest'`


Expected: FAIL porque el UI state no tiene progreso persistente.

- [ ] **Step 3: Reemplazar contrato de acciones**

```kotlin
interface CaptureActions {
    suspend fun startNewDay()
    suspend fun resume(id: SessionId)
    suspend fun pause()
    suspend fun markHomework(sessionId: SessionId, blockId: BlockId)
    suspend fun finalizeDay(id: SessionId, state: SessionState)
    fun startProcessing(id: SessionId, mode: InterpretationMode)
    suspend fun pauseProcessing(id: SessionId)
    fun resumeProcessing(id: SessionId, mode: InterpretationMode)
    suspend fun saveDraft(draft: DiaryDraftEntity)
    suspend fun approveAndClean(draft: DiaryDraftEntity): CleanupOutcome
    suspend fun retryCleanup(sessionId: SessionId): CleanupOutcome
}
```

- [ ] **Step 4: Derivar UI desde Room**

Agregar a `CaptureUiState`: `processedMs`, `processingTotalMs`, `progressPercent`, `progressLabel`, `transcriptionPaused` y `processingFailure`. El `ViewModel` combina el flow de ejecución con sesiones, borradores y evidencia; no mantiene un job de inferencia propio. `PREPARING` muestra `PREPARANDO MODELO`; no se muestra tiempo restante estimado.

- [ ] **Step 5: Sustituir sección visual**


Mostrar `WHISPER LOCAL · ESPAÑOL`, `MODELO INTEGRADO`, barra determinada, porcentaje, tiempo procesado/total, bloque/tramo y botones `PAUSAR PROCESAMIENTO`, `RETOMAR` o `REINTENTAR`. Eliminar de UI y ViewModel `onRequestModel`, `SpanishModelDownloadState`, `modelAllowsProcessing` y todos los textos de descarga. Los archivos del motor anterior se eliminan juntos en Task 9 para mantener compilables los commits intermedios.

- [ ] **Step 6: Ejecutar pruebas de UI y regresión**

Run: `./gradlew testDebugUnitTest --tests '*CaptureViewModelTest' --tests '*FullJourneyTest'`

Expected: PASS; ningún test espera descarga de idioma.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase app/src/test/java/com/capo/diarioclase/ui/capture app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt
git commit -m "feat: show persistent Whisper progress and controls"
```

### Task 8: Limpieza segura de los nuevos temporales

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/diary/cleanup/CleanupCoordinator.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/diary/cleanup/CleanupCoordinatorTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`

**Interfaces:**
- Consumes: diario permanente verificado, checkpoints y ejecución de la sesión.
- Produces: cero archivos y cero filas temporales solo después de aprobación.

- [ ] **Step 1: Escribir prueba roja de conservación ante fallo**

```kotlin
@Test fun `Whisper failure retains audio checkpoints and partial spans`() = runTest {
    coordinator.processNext(sessionId, InterpretationMode.CONSERVATIVE)
    assertTrue(files.exists(segmentId))
    assertEquals(1, dao.checkpoints(sessionId.value).size)
    assertTrue(dao.transcript(sessionId.value).isNotEmpty())
    assertNull(dao.diaryBySession(sessionId.value))
}
```

- [ ] **Step 2: Escribir prueba roja de limpieza posterior a aprobación**

Tras `approveAndClean`, afirmar `files.exists(segmentId) == false`, checkpoints vacíos, run inexistente, transcript vacío y diario permanente exactamente igual al borrador aprobado.

- [ ] **Step 3: Incorporar filas nuevas al conteo y borrado temporal**

Agregar `deleteTranscriptionCheckpointsForSession`, `deleteTranscriptionRun` y ambos conteos a `temporaryRowCount`. `RoomTemporaryCleanupStore.deleteSessionTemporaryRows` los elimina en la misma etapa que transcript, evidencia y borrador.

- [ ] **Step 4: Ejecutar pruebas de seguridad**

Run: `./gradlew testDebugUnitTest --tests '*CleanupCoordinatorTest' --tests '*FullJourneyTest'`

Expected: PASS; los recorridos de fallo conservan audio y los recorridos aprobados dejan cero temporales.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt app/src/main/java/com/capo/diarioclase/diary/cleanup app/src/test/java/com/capo/diarioclase/diary/cleanup app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt
git commit -m "test: preserve Whisper data until verified approval"
```

### Task 9: Composición, versión, CI y guía empírica

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/android-apk.yml`
- Delete: `app/src/main/java/com/capo/diarioclase/processing/transcription/AndroidOnDeviceTranscriptionEngine.kt`
- Delete: `app/src/main/java/com/capo/diarioclase/processing/transcription/RecognitionDeadline.kt`
- Delete: `app/src/main/java/com/capo/diarioclase/processing/transcription/SpanishModelSupport.kt`
- Delete: `app/src/test/java/com/capo/diarioclase/processing/transcription/RecognitionDeadlineTest.kt`
- Delete: `app/src/test/java/com/capo/diarioclase/processing/transcription/SpanishModelSupportTest.kt`
- Delete: `app/src/androidTest/java/com/capo/diarioclase/processing/transcription/AudioPipeConsumptionTest.kt`
- Create: `PHASE4_WHISPER_DEVICE_TEST.md`
- Test: todos los módulos Android.

**Interfaces:**
- Consumes: todos los componentes anteriores.
- Produces: APK debug `0.4.0-whisper`, artefacto CI y protocolo de prueba física.

- [ ] **Step 1: Componer dependencias finales**

`DiarioClaseApp` crea `WhisperModelInstaller`, runtime JNI, `WhisperTranscriptionEngine`, `TranscriptionCoordinator` y `TranscriptionWorkScheduler`; registra `MIGRATION_3_4`. Eliminar `AndroidOnDeviceTranscriptionEngine`, `RecognitionDeadline`, `SpanishModelSupport` y sus pruebas. Retirar del enum los códigos `ON_DEVICE_UNAVAILABLE`, `LANGUAGE_UNAVAILABLE`, `AUDIO_SOURCE_UNSUPPORTED`, `RECOGNIZER_BUSY` y `UNKNOWN`. Eliminar la recuperación que convertía cualquier `TRANSCRIBING` en fallo al abrir.

- [ ] **Step 2: Limpiar manifiesto y versiónar**

Eliminar `<queries>` de `RecognitionService`; confirmar que no existe permiso `INTERNET`; mantener permisos de micrófono, notificaciones y foreground service para grabación. Cambiar a `versionCode = 5` y `versionName = "0.4.0-whisper"`.

- [ ] **Step 3: Escribir guía empírica exacta**

`PHASE4_WHISPER_DEVICE_TEST.md` debe ordenar: instalar, desactivar Wi-Fi y datos móviles, grabar 10 segundos con los cinco campos, procesar, registrar duración, verificar texto/ficha, probar pausa y reanudación con 1 minuto, cerrar y reabrir con 10 minutos, y solo después probar un bloque largo. Debe advertir que no se apruebe la ficha durante un recorrido de fallo y que el APK puede requerir desinstalación por la firma debug efímera actual.

- [ ] **Step 4: Ejecutar verificación completa local**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"`

Expected: exit `0`; todas las pruebas, lint, APK de aplicación y APK instrumental compilados.

- [ ] **Step 5: Verificar APK y modelo empaquetado**

Run: `unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'ggml-base.bin|lib/arm64-v8a/libdiarioclase_whisper.so'`

Expected: aparecen exactamente el modelo y la biblioteca ARM64.

Run: `apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk | rg 'android.permission.INTERNET'`

Expected: exit `1` y ninguna salida.

- [ ] **Step 6: Confirmar workflow en `main`**

El workflow debe ejecutar la verificación completa, publicar `DiarioClase-Android-debug` y activarse para `push` a `main` y `workflow_dispatch`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt app/src/main/java/com/capo/diarioclase/processing/transcription app/src/test/java/com/capo/diarioclase/processing/transcription app/src/androidTest/java/com/capo/diarioclase/processing/transcription app/src/main/AndroidManifest.xml app/build.gradle.kts .github/workflows/android-apk.yml PHASE4_WHISPER_DEVICE_TEST.md
git commit -m "release: prepare offline Spanish Whisper APK"
```

## Handoff y release gate

No entregar el APK como funcional únicamente porque compile. Antes de la entrega:

1. Confirmar resultado exitoso del workflow sobre `main`.
2. Descargar el artefacto exacto de ese run.
3. Verificar integridad del ZIP y SHA-256 del APK.
4. Informar que la firma debug todavía puede impedir una actualización sobre la instalación anterior.
5. Ejecutar en el Moto g max la prueba de 10 segundos de `PHASE4_WHISPER_DEVICE_TEST.md`.
6. Si la prueba física falla, conservar el audio y capturar estado, código y progreso antes de modificar el diseño.

# Gemini-Assisted Contextual Interpretation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Incorporar interpretación semántica automática con Gemini Flash sobre la transcripción local de Whisper, validando toda afirmación contra evidencia y conservando caché, modos, edición segura y fallback local.

**Architecture:** Whisper continúa offline y produce spans inmutables. Un cliente Gemini recibe únicamente paquetes de texto identificados, devuelve JSON estructurado y un validador local lo convierte en claims; la reducción, los modos, el caché y la protección de campos editados permanecen deterministas en el teléfono. La clave se ingresa en la app y se cifra con Android Keystore, sin aparecer en el repositorio ni en el APK.

**Tech Stack:** Kotlin 2.x, Android SDK 35, Jetpack Compose, Room, WorkManager, Android Keystore, `HttpURLConnection`, `kotlinx-serialization-json`, JUnit 4 y Robolectric.

**Spec:** `docs/superpowers/specs/2026-09-14-contextual-interpretation-design.md`

## Global Constraints

- Whisper `base` continúa local, fijo en español y sin cambios semánticos.
- Nunca se envía audio, rutas de archivos, ids internos de sesión ni metadatos del dispositivo.
- Gemini recibe únicamente texto, ids artificiales de spans y tiempos relativos.
- El nivel gratuito requiere consentimiento explícito porque Google puede usar el contenido para mejorar productos.
- La clave no se incluye en código, recursos, GitHub, APK, Room, logs ni backups.
- La app agrega Internet exclusivamente para interpretar texto con Gemini.
- `CONSERVATIVE` continúa como modo predeterminado.
- Cambiar de modo no retranscribe ni vuelve a llamar a Gemini.
- Los campos editados por el usuario nunca se sobrescriben.
- Audio y temporales solo se borran después de aprobar y verificar el diario permanente.
- Sin clave, sin red o tras agotar reintentos, la app conserva todo y ofrece fallback local.
- Ninguna prueba de CI realiza llamadas reales ni contiene credenciales.
- Cada tarea se sube a `feature/phase5-contextual-interpretation` y debe tener GitHub Actions verde antes de continuar.

---

### Task 1: Contratos, escenarios sintéticos y cliente falso

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/SemanticInterpretationModels.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/SemanticInterpretationClient.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/SyntheticSemanticScenarios.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/FakeSemanticInterpretationClient.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/SemanticInterpretationContractTest.kt`

**Interfaces:**
- Consumes: paquetes de spans textuales.
- Produces: `suspend fun interpret(request: SemanticInterpretationRequest): SemanticInterpretationOutcome`.

- [ ] **Step 1: Crear tipos de entrada y salida**

```kotlin
data class SemanticSpan(
    val publicId: String,
    val blockOrdinal: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val contextOnly: Boolean,
)

data class SemanticInterpretationRequest(
    val packetId: String,
    val promptVersion: String,
    val schemaVersion: String,
    val modelId: String,
    val spans: List<SemanticSpan>,
)

sealed interface SemanticInterpretationOutcome {
    data class Success(val rawJson: String) : SemanticInterpretationOutcome
    data class Failure(
        val code: SemanticFailure,
        val retryable: Boolean,
        val httpStatus: Int? = null,
    ) : SemanticInterpretationOutcome
}

enum class SemanticFailure {
    NOT_CONFIGURED,
    CONSENT_REQUIRED,
    NO_NETWORK,
    AUTHENTICATION,
    QUOTA,
    SERVER_UNAVAILABLE,
    TIMEOUT,
    EMPTY_RESPONSE,
    INVALID_RESPONSE,
    INTERNAL,
}

fun interface SemanticInterpretationClient {
    suspend fun interpret(
        request: SemanticInterpretationRequest,
    ): SemanticInterpretationOutcome
}
```

- [ ] **Step 2: Crear escenarios sin corpus real**

La matriz mínima debe contener spans sintéticos para:

```text
Hoy trabajamos el contraste entre perfecto e indefinido.
Vamos a la página cuarenta y dos.
Hacemos los ejercicios tres y cuatro.
El cuatro no, perdón, queda para casa.
La próxima clase vamos a ver los pronombres.
¿Hicieron el ejercicio cinco?
```

Esperar tema de pasados, página 42, ejercicio 3 realizado y ejercicio 4 como tarea. Prohibir pronombres como tema realizado y ejercicio 5 como realizado o asignado.

Agregar escenarios independientes con números escritos, listas, rangos, cambios de página, preguntas, citas, negaciones, autocorrecciones, repeticiones legítimas y duplicados entre paquetes.

- [ ] **Step 3: Implementar el cliente falso**

`FakeSemanticInterpretationClient` guarda cada request recibido y devuelve resultados encolados. Debe permitir verificar cantidad de llamadas, modelo, paquetes y reintentos sin usar red.

- [ ] **Step 4: Escribir el contract test rojo**

El contract test construye `SemanticInterpretationRequest`, invoca la futura tubería híbrida y afirma categorías, estados, evidencias, exclusiones y ausencia de llamadas adicionales al cambiar de modo.

- [ ] **Step 5: Ejecutar para confirmar rojo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*SemanticInterpretationContractTest'
```

Expected: FAIL porque la tubería todavía no existe.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/semantic app/src/test/java/com/capo/diarioclase/processing/semantic
git commit -m "test: define Gemini interpretation contract"
```

---

### Task 2: Consentimiento y credencial cifrada

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/GeminiSettings.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/GeminiCredentialStore.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/GeminiSettingsTest.kt`
- Create: `app/src/androidTest/java/com/capo/diarioclase/processing/semantic/GeminiCredentialStoreTest.kt`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`

**Interfaces:**
- Consumes: clave introducida por el usuario y decisión de consentimiento.
- Produces: `GeminiConfiguration(enabled, consentVersion, modelId)` y acceso cifrado mediante `CredentialStore`.

- [ ] **Step 1: Escribir pruebas de configuración**

Probar que Gemini no puede habilitarse sin consentimiento `gemini-free-data-v1`; que revocarlo deshabilita llamadas; que el modelo predeterminado es `gemini-3-flash-preview`; y que el id puede cambiarse sin alterar respuestas cacheadas de otro modelo.

- [ ] **Step 2: Definir interfaces**

```kotlin
data class GeminiConfiguration(
    val enabled: Boolean,
    val consentVersion: String?,
    val modelId: String = "gemini-3-flash-preview",
)

interface CredentialStore {
    fun hasCredential(): Boolean
    fun saveCredential(value: CharArray)
    fun readCredential(): CharArray?
    fun clearCredential()
}
```

- [ ] **Step 3: Implementar almacenamiento con Keystore**

Crear una clave AES/GCM no exportable bajo el alias `diarioclase.gemini.credential.v1`. Cifrar la credencial y escribir nonce más ciphertext mediante `AtomicFile` dentro de `noBackupFilesDir`. Sobrescribir los `CharArray` temporales después de usarlos. No registrar excepciones con valores sensibles.

- [ ] **Step 4: Excluir credenciales de extracción y backups**

Agregar reglas explícitas para excluir cualquier archivo `gemini-credential-*`. Verificar que el archivo real vive en `noBackupFilesDir`.

- [ ] **Step 5: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*GeminiSettingsTest' assembleDebugAndroidTest
```

Expected: PASS y APK instrumental compilado.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/semantic app/src/test/java/com/capo/diarioclase/processing/semantic app/src/androidTest/java/com/capo/diarioclase/processing/semantic app/src/main/res/xml
git commit -m "feat: store Gemini consent and credential securely"
```

---

### Task 3: Paquetes contextuales y minimización de datos

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/GeminiInterpretationPacketBuilder.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/GeminiInterpretationPacketBuilderTest.kt`

**Interfaces:**
- Consumes: `List<TranscriptSpan>`.
- Produces: `fun build(spans: List<TranscriptSpan>, modelId: String): List<SemanticInterpretationRequest>`.

- [ ] **Step 1: Escribir pruebas de empaquetado**

Probar que:

- los spans se ordenan por bloque y timestamp;
- un paquete nunca mezcla bloques;
- se corta antes de superar 12.000 caracteres;
- se prefiere una pausa de 4.000 ms como corte;
- los últimos dos spans reaparecen como `contextOnly = true`;
- cada id público es secuencial, como `B2-S17`;
- no aparecen ids de sesión, segmentos, rutas ni nombres de archivo;
- el texto del span se conserva literalmente.

- [ ] **Step 2: Ejecutar para confirmar rojo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*GeminiInterpretationPacketBuilderTest'
```

Expected: FAIL por clase inexistente.

- [ ] **Step 3: Implementar el constructor**

```kotlin
class GeminiInterpretationPacketBuilder(
    private val maxCharacters: Int = 12_000,
    private val preferredPauseMs: Long = 4_000,
    private val overlapSpans: Int = 2,
    private val promptVersion: String = "gemini-ele-v1",
    private val schemaVersion: String = "claims-v1",
) {
    fun build(
        spans: List<TranscriptSpan>,
        modelId: String,
    ): List<SemanticInterpretationRequest>
}
```

El `packetId` debe ser SHA-256 de versión, modelo y representación textual del paquete. No incluir ids internos.

- [ ] **Step 4: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*GeminiInterpretationPacketBuilderTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/semantic/GeminiInterpretationPacketBuilder.kt app/src/test/java/com/capo/diarioclase/processing/semantic/GeminiInterpretationPacketBuilderTest.kt
git commit -m "feat: build minimized contextual Gemini packets"
```

---

### Task 4: Prompt, JSON estructurado y cliente Gemini

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/GeminiPromptFactory.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/GeminiHttpTransport.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/GeminiApiClient.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/GeminiPromptFactoryTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/GeminiApiClientTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `SemanticInterpretationRequest` y credencial.
- Produces: implementación de `SemanticInterpretationClient`.

- [ ] **Step 1: Agregar parser JSON**

Agregar `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`. No aplicar el plugin de serialización; analizar mediante `JsonElement` para que campos inesperados puedan validarse explícitamente.

- [ ] **Step 2: Escribir pruebas del prompt**

Afirmar que el prompt:

- enumera solo las cinco categorías y seis estados;
- exige evidencia mediante ids;
- prohíbe inferencias sin evidencia;
- distingue preguntas, planes futuros, citas y correcciones;
- solicita extracción exhaustiva independiente del modo;
- marca spans `contextOnly`;
- no contiene la clave ni identificadores internos.

- [ ] **Step 3: Implementar `GeminiPromptFactory`**

Crear `systemInstruction`, texto de spans y `responseJsonSchema`. El esquema exige `claims`, limita enums y declara `additionalProperties: false`.

- [ ] **Step 4: Escribir pruebas del cliente con transporte falso**

Cubrir respuestas 200, 400, 401/403, 429, 500/503, timeout, cuerpo vacío y JSON de envoltura sin candidato. Verificar encabezado `x-goog-api-key` y que su valor nunca aparece en excepciones.

- [ ] **Step 5: Implementar transporte**

```kotlin
interface GeminiHttpTransport {
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
    ): HttpTransportResult
}

data class HttpTransportResult(
    val status: Int,
    val body: String,
)
```

La implementación usa `HttpsURLConnection`, `connectTimeout = 30_000`, `readTimeout = 90_000`, UTF-8 y cierre seguro de streams.

- [ ] **Step 6: Implementar cliente**

Usar:

```text
https://generativelanguage.googleapis.com/v1beta/models/{modelId}:generateContent
```

Enviar la clave solamente en `x-goog-api-key`. Extraer el texto JSON del primer candidato. Mapear 401/403 a `AUTHENTICATION`, 429 a `QUOTA`, 500/503 a `SERVER_UNAVAILABLE` y timeouts a `TIMEOUT`.

- [ ] **Step 7: Agregar permiso de Internet**

Agregar solamente:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

No modificar permisos de grabación ni foreground service.

- [ ] **Step 8: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*GeminiPromptFactoryTest' --tests '*GeminiApiClientTest'
```

Expected: PASS sin tráfico real.

- [ ] **Step 9: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/capo/diarioclase/processing/semantic app/src/test/java/com/capo/diarioclase/processing/semantic
git commit -m "feat: call Gemini with evidence-bound structured output"
```

---

### Task 5: Validación, reducción y modos

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/GeminiResponseValidator.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/SemanticClaimReducer.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/GeminiResponseValidatorTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/SemanticClaimReducerTest.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/evidence/InterpretationProjector.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/evidence/InterpretationProjectorTest.kt`

**Interfaces:**
- Consumes: JSON crudo y mapa de `SemanticSpan`.
- Produces: `ValidationOutcome.Valid(List<RawClaim>)` o `ValidationOutcome.Invalid`.

- [ ] **Step 1: Escribir pruebas de validación adversarial**

Rechazar:

- categoría o estado desconocidos;
- confianza fuera de `0.0..1.0`;
- value vacío o mayor a 300 caracteres;
- más de 100 claims;
- ids inexistentes;
- evidencia compuesta solo por spans `contextOnly`;
- página o ejercicio sin soporte textual ni contexto de página;
- propiedades no previstas;
- JSON truncado.

Aceptar autocorrecciones que indiquen `supersedes_claim_keys` válidas.

- [ ] **Step 2: Implementar validador estricto**

```kotlin
sealed interface ValidationOutcome {
    data class Valid(val claims: List<RawClaim>) : ValidationOutcome
    data class Invalid(val reason: ValidationFailure) : ValidationOutcome
}

enum class ValidationFailure {
    MALFORMED_JSON,
    UNKNOWN_FIELD,
    UNKNOWN_ENUM,
    INVALID_VALUE,
    INVALID_CONFIDENCE,
    TOO_MANY_CLAIMS,
    MISSING_EVIDENCE,
    UNKNOWN_EVIDENCE,
    UNSUPPORTED_REFERENCE,
}
```

Todos los `RawClaim` de Gemini usan `ClaimOrigin.SEMANTIC` y construyen `EvidenceRef` desde spans locales, nunca desde texto devuelto por el modelo.

- [ ] **Step 3: Escribir pruebas del reductor**

Probar duplicados por solapamiento, misma página en paquetes sucesivos, ejercicio corregido, ejercicio movido a tarea, cancelación posterior y conflicto ambiguo.

- [ ] **Step 4: Implementar reducción**

`SemanticClaimReducer.reduce(packetClaims)` procesa claims por bloque y tiempo. Conserva claims reemplazados con `active = false`; no cancela otros ejercicios de una lista; y transforma conflictos sin referente único en `UNCERTAIN`.

- [ ] **Step 5: Confirmar que los modos son locales**

Mantener los umbrales actuales y agregar una prueba que proyecte los mismos claims en los tres modos sin invocar ningún cliente.

- [ ] **Step 6: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*GeminiResponseValidatorTest' --tests '*SemanticClaimReducerTest' --tests '*InterpretationProjectorTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing app/src/test/java/com/capo/diarioclase/processing
git commit -m "feat: validate and reduce Gemini claims locally"
```

---

### Task 6: Caché persistente y migración Room

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/RoomInterpretationCache.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/RoomInterpretationCacheTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`

**Interfaces:**
- Consumes: `packetId`, modelo, versiones y JSON ya validado.
- Produces: `InterpretationCache.get(packetId)` y `putValidated(entry)`.

- [ ] **Step 1: Escribir pruebas rojas**

Probar acierto por hash idéntico, fallo al cambiar texto, modelo, prompt o esquema; ausencia de caché para respuesta inválida; conservación ante cierre; y eliminación solamente después de aprobar.

- [ ] **Step 2: Agregar entidad**

```kotlin
@Entity(tableName = "interpretation_cache")
data class InterpretationCacheEntity(
    @PrimaryKey val packetId: String,
    val sessionId: String,
    val modelId: String,
    val promptVersion: String,
    val schemaVersion: String,
    val validatedJson: String,
    val createdAtEpochMs: Long,
)
```

- [ ] **Step 3: Implementar migración Room 4→5**

Crear tabla e índice por `sessionId`. Registrar `MIGRATION_4_5` sin tocar audio, transcript, checkpoints, evidencia, borradores ni diarios.

- [ ] **Step 4: Incorporar limpieza segura**

Agregar consulta, inserción, borrado por sesión y conteo temporal. `CleanupCoordinator` debe verificar que no quede caché después de aprobar; ante cualquier fallo conserva todas las filas y el audio.

- [ ] **Step 5: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*RoomInterpretationCacheTest' --tests '*FullJourneyTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/data app/src/main/java/com/capo/diarioclase/processing/semantic app/src/test/java/com/capo/diarioclase
git commit -m "feat: cache validated Gemini interpretation packets"
```

---

### Task 7: Coordinador híbrido, reintentos y fallback

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/HybridInterpretationCoordinator.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/FallbackClaimExtractor.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/HybridInterpretationCoordinatorTest.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionCoordinator.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionWorker.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/work/TranscriptionCoordinatorTest.kt`

**Interfaces:**
- Consumes: spans completos, configuración, credencial, cliente, caché, validador, reductor y modo.
- Produces: `suspend fun interpret(sessionId, spans, mode, forceRefresh): HybridInterpretationOutcome`.

- [ ] **Step 1: Escribir pruebas de recorridos**

Cubrir:

- Gemini configurado y respuesta válida;
- respuesta servida desde caché sin llamada;
- `429`, `500` y `503` con tres intentos;
- esperas de 2.000, 5.000 y 12.000 ms mediante `RetryDelay` falso;
- error 401 sin reintentos;
- timeout de 90 segundos;
- paquete inválido conservado como fallo;
- procesamiento parcial reanudado desde paquetes cacheados;
- fallback cuando no hay clave, consentimiento o red;
- `forceRefresh = true` ignora caché pero no borra la entrada válida anterior hasta reemplazarla.

- [ ] **Step 2: Implementar fallback local**

Encapsular las reglas existentes en `FallbackClaimExtractor`. Debe dejar claro `ClaimOrigin.LOCAL_RULE` y no pretender resolver correcciones complejas. Mantener una ficha editable aunque Gemini no esté disponible.

- [ ] **Step 3: Implementar coordinador híbrido**

Procesar paquetes secuencialmente. Para cada paquete:

1. buscar caché;
2. llamar a Gemini si falta;
3. reintentar solo fallos recuperables;
4. validar;
5. guardar únicamente JSON válido;
6. continuar con el siguiente paquete;
7. reducir claims y proyectar el modo.

Si ningún paquete puede interpretarse con Gemini, usar fallback. Si algunos paquetes tienen éxito y otros fallan, conservar los válidos y colocar los resultados del fallback de los bloques faltantes en “por confirmar”.

- [ ] **Step 4: Integrar después de Whisper**

Reemplazar `LiteralClaimExtractor` y `ClaimReducer` dentro de `TranscriptionCoordinator` por `HybridInterpretationCoordinator`. No cambiar transcripción, checkpoints ni deduplicación.

- [ ] **Step 5: Ajustar timeout del worker**

Mantener 300.000 ms por ventana Whisper y aplicar 90.000 ms por solicitud Gemini dentro del coordinador. Una cancelación debe cerrar la conexión y conservar caché y transcript confirmados.

- [ ] **Step 6: Componer dependencias**

`DiarioClaseApp` construye stores, packet builder, prompt, transporte, cliente, validador, reductor, caché, fallback y coordinador. La credencial se lee solo inmediatamente antes de una llamada y el buffer se limpia después.

- [ ] **Step 7: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*HybridInterpretationCoordinatorTest' --tests '*TranscriptionCoordinatorTest' --tests '*SemanticInterpretationContractTest'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase app/src/test/java/com/capo/diarioclase
git commit -m "feat: integrate resilient Gemini interpretation fallback"
```

---

### Task 8: Ajustes, consentimiento y estado visible

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureUiState.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureViewModel.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureScreen.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/ui/capture/CaptureViewModelTest.kt`

**Interfaces:**
- Consumes: activar/desactivar Gemini, aceptar consentimiento, guardar/probar/borrar clave y reintentar.
- Produces: estado de configuración y progreso de interpretación sin exponer secretos.

- [ ] **Step 1: Escribir pruebas de UI state**

Probar estados:

`NO_CONFIGURADO`, `CONSENTIMIENTO_REQUERIDO`, `LISTO`, `INTERPRETANDO`, `REINTENTANDO`, `CUOTA_AGOTADA`, `SIN_CONEXION`, `RESPUESTA_INVALIDA`, `FALLBACK_LOCAL` y `COMPLETADO`.

Verificar que el state solo contiene `credentialSuffix` de cuatro caracteres y nunca la clave completa.

- [ ] **Step 2: Agregar panel de configuración**

Incluir:

- interruptor `INTERPRETACIÓN CON GEMINI`;
- texto claro sobre envío de transcripción y uso de datos del nivel gratuito;
- aceptación explícita;
- campo de clave con contenido oculto;
- `GUARDAR CLAVE`, `PROBAR CONEXIÓN` y `BORRAR CLAVE`;
- modelo visible como información avanzada;
- estado de la última prueba.

No enviar transcripciones durante `PROBAR CONEXIÓN`; usar un prompt fijo de una palabra y descartar la respuesta.

- [ ] **Step 3: Mostrar progreso y procedencia**

Durante interpretación mostrar paquete actual y total. En la ficha indicar `GEMINI`, `LOCAL` o `MIXTO`. No mostrar mensajes que impliquen que una ficha local tiene precisión semántica equivalente.

- [ ] **Step 4: Agregar recuperación**

Mostrar `REINTENTAR GEMINI` para fallos recuperables y `USAR FICHA LOCAL` como decisión explícita. No eliminar ni retranscribir audio.

- [ ] **Step 5: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*CaptureViewModelTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/ui app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt app/src/test/java/com/capo/diarioclase/ui
git commit -m "feat: add Gemini consent configuration and status UI"
```

---

### Task 9: Feedback local, validación integral y APK

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`
- Create: `PHASE5_GEMINI_DEVICE_TEST.md`
- Create: `PHASE5_HANDOFF.md`
- Modify: `AGENTS.md`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: borrador generado, edición aprobada y toda la implementación anterior.
- Produces: feedback local, versión `0.5.0-gemini`, documentación durable, CI verde y APK de prueba.

- [ ] **Step 1: Escribir pruebas de feedback**

Probar que editar una ficha guarda valores generados y aprobados, procedencia y ids de claims; aprobar sin cambios no crea feedback; no se guarda audio, transcripción ni clave; y el feedback no modifica futuras interpretaciones automáticamente.

- [ ] **Step 2: Agregar entidad y migración 5→6**

```kotlin
@Entity(tableName = "interpretation_feedback")
data class InterpretationFeedbackEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val mode: String,
    val origin: String,
    val generatedFieldsJson: String,
    val approvedFieldsJson: String,
    val evidenceClaimIdsJson: String,
    val createdAtEpochMs: Long,
)
```

Guardar feedback en la misma transacción que el diario permanente, antes de limpiar temporales.

- [ ] **Step 3: Escribir protocolo físico**

Probar en Moto g max:

1. clave válida y red disponible;
2. texto con los cinco campos y una autocorrección;
3. cambio de modo sin nueva llamada;
4. cierre y reapertura usando caché;
5. clave inválida;
6. modo avión con fallback;
7. simulación de `429` o `503` mediante transporte de debug;
8. edición, aprobación y limpieza;
9. verificación de que la clave sigue disponible y el audio fue eliminado solo tras aprobar.

- [ ] **Step 4: Actualizar versión**

Cambiar `versionCode` de 6 a 7 y `versionName` a `0.5.0-gemini`.

- [ ] **Step 5: Ejecutar verificación completa**

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
```

Expected: exit 0.

- [ ] **Step 6: Verificar APK y secretos**

Run:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'ggml-base.bin|lib/arm64-v8a/libdiarioclase_whisper.so'
apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk | rg 'android.permission.INTERNET|android.permission.ACCESS_NETWORK_STATE'
rg -n --hidden --glob '!build/**' --glob '!.git/**' 'AIza|x-goog-api-key.{0,80}[A-Za-z0-9_-]{20,}' .
```

Expected: modelo y biblioteca presentes; ambos permisos de red presentes; búsqueda de credenciales sin coincidencias reales.

- [ ] **Step 7: Actualizar continuidad**

`PHASE5_HANDOFF.md` registra por tarea commit, workflow, cambios, fallos, prueba física y próximo paso. `AGENTS.md` indica leer primero diseño, plan y handoff de Fase 5, conservando Fase 4 como recuperación conocida.

- [ ] **Step 8: Commit y CI**

```bash
git add app/src/main/java/com/capo/diarioclase app/src/test/java/com/capo/diarioclase app/src/androidTest/java/com/capo/diarioclase PHASE5_GEMINI_DEVICE_TEST.md PHASE5_HANDOFF.md AGENTS.md app/build.gradle.kts
git commit -m "release: prepare Gemini interpretation APK"
```

Esperar GitHub Actions verde antes de entregar el APK.

- [ ] **Step 9: Cierre empírico**

Instalar el APK exacto de la CI y completar `PHASE5_GEMINI_DEVICE_TEST.md`. Cada fallo semántico se convierte en un escenario sintético mínimo con respuesta esperada; no se exige entregar audio ni formar un corpus.

## Decisiones posteriores

- No agregar una segunda llamada de consolidación mientras la reducción local resuelva duplicados.
- No enviar bloques completos nuevamente al cambiar de modo.
- No incorporar marcadores manuales salvo que la validación física muestre una necesidad recurrente.
- Si el modelo configurado pierde nivel gratuito o se retira, cambiar `modelId`, incrementar `promptVersion` cuando corresponda e invalidar el caché por clave compuesta.
- Si la aplicación deja de ser privada, reemplazar credencial directa por Firebase AI Logic con App Check antes de distribuirla.

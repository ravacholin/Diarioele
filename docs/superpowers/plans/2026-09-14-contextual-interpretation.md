# Free Multi-Provider Interpretation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar interpretación semántica automática con Gemini como principal, Groq y OpenRouter Free como fallbacks remotos, y un extractor local como fallback final, sin corpus inicial ni cambio automático a servicios pagos.

**Architecture:** Whisper continúa local y produce spans inmutables. Un router secuencial entrega cada paquete textual al primer proveedor gratuito configurado que responda correctamente; un contrato y validador comunes convierten respuestas heterogéneas en claims con evidencia. Caché, modos, reducción, edición y fallback local permanecen deterministas en Android.

**Tech Stack:** Kotlin 2.x, Android SDK 35, Jetpack Compose, Room, WorkManager, Android Keystore, `HttpsURLConnection`, `kotlinx-serialization-json`, JUnit 4 y Robolectric.

**Spec:** `docs/superpowers/specs/2026-09-14-contextual-interpretation-design.md`

## Global Constraints

- Proveedores remotos permitidos: Gemini, Groq y `openrouter/free`.
- Orden predeterminado: Gemini, Groq, OpenRouter, local.
- No incorporar Cloudflare, Mistral, servidor propio ni segundo modelo Android.
- No admitir ids de modelos pagos ingresados libremente.
- Cada proveedor remoto requiere clave y consentimiento independientes.
- Las claves pertenecen a proyectos o cuentas sin facturación habilitada.
- La aplicación nunca envía audio, rutas ni ids internos.
- La aplicación nunca consulta dos proveedores en paralelo.
- Una respuesta validada detiene la cadena.
- `429` avanza inmediatamente al siguiente proveedor.
- `500`, `503` y timeout admiten un solo reintento por proveedor.
- Ninguna prueba de CI usa claves reales ni acceso de red.
- Cambiar de modo no retranscribe ni consume inferencia.
- Los campos editados no se sobrescriben.
- Ningún fallo elimina audio, transcript, caché válido ni evidencia.
- Cada tarea se sube a `feature/phase5-contextual-interpretation` y se valida con GitHub Actions.

---

### Task 1: Contrato común y escenarios sintéticos

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/InferenceModels.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/InferenceProviderClient.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/FakeInferenceProviderClient.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/SyntheticInterpretationScenarios.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/InferenceContractTest.kt`

**Interfaces:**
- Consumes: `InterpretationRequest`.
- Produces: `suspend fun infer(request): ProviderOutcome`.

- [ ] **Step 1: Crear modelos comunes**

```kotlin
enum class InferenceProvider { GEMINI, GROQ, OPENROUTER }

data class ProviderProfile(
    val provider: InferenceProvider,
    val modelId: String,
    val enabled: Boolean,
    val consentVersion: String?,
)

data class PublicTranscriptSpan(
    val publicId: String,
    val blockOrdinal: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val contextOnly: Boolean,
)

data class InterpretationRequest(
    val packetId: String,
    val promptVersion: String,
    val schemaVersion: String,
    val spans: List<PublicTranscriptSpan>,
)

sealed interface ProviderOutcome {
    data class Success(
        val provider: InferenceProvider,
        val modelId: String,
        val rawJson: String,
    ) : ProviderOutcome

    data class Failure(
        val provider: InferenceProvider,
        val code: ProviderFailure,
        val retryable: Boolean,
        val httpStatus: Int? = null,
        val retryAfterMs: Long? = null,
    ) : ProviderOutcome
}

enum class ProviderFailure {
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

fun interface InferenceProviderClient {
    suspend fun infer(request: InterpretationRequest): ProviderOutcome
}
```

- [ ] **Step 2: Crear escenarios sintéticos**

Incluir el recorrido:

```text
Hoy trabajamos el contraste entre perfecto e indefinido.
Vamos a la página cuarenta y dos.
Hacemos los ejercicios tres y cuatro.
El cuatro no, perdón, queda para casa.
La próxima clase vamos a ver los pronombres.
¿Hicieron el ejercicio cinco?
```

Esperar página 42, ejercicio 3 realizado y ejercicio 4 como tarea. Prohibir pronombres como tema realizado y ejercicio 5 como realizado o asignado.

Agregar listas, rangos, números en palabras, cambio de página, citas, negaciones, autocorrecciones, repetición legítima y duplicación entre paquetes.

- [ ] **Step 3: Implementar fake configurable**

`FakeInferenceProviderClient` recibe proveedor y cola de outcomes; registra requests e intentos. Permite simular 401, 429, 500, 503, timeout, JSON inválido y éxito.

- [ ] **Step 4: Escribir pruebas rojas del contrato**

Afirmar que un resultado final solo contiene claims con evidencia local válida y que la procedencia coincide con el proveedor que produjo el JSON.

- [ ] **Step 5: Ejecutar rojo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*InferenceContractTest'
```

Expected: FAIL porque el router y el validador todavía no existen.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/semantic app/src/test/java/com/capo/diarioclase/processing/semantic
git commit -m "test: define free inference provider contract"
```

---

### Task 2: Configuración free-only, consentimiento y claves cifradas

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/FreeProviderCatalog.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/ProviderSettingsStore.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/ProviderCredentialStore.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/FreeProviderCatalogTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/ProviderSettingsStoreTest.kt`
- Create: `app/src/androidTest/java/com/capo/diarioclase/processing/semantic/ProviderCredentialStoreTest.kt`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`

**Interfaces:**
- Consumes: claves y decisiones del usuario.
- Produces: perfiles habilitados en orden y credenciales descifradas solo durante una llamada.

- [ ] **Step 1: Escribir pruebas del catálogo**

```kotlin
object FreeProviderCatalog {
    val profiles = listOf(
        ProviderProfile(
            InferenceProvider.GEMINI,
            "gemini-3-flash-preview",
            enabled = false,
            consentVersion = null,
        ),
        ProviderProfile(
            InferenceProvider.GROQ,
            "openai/gpt-oss-20b",
            enabled = false,
            consentVersion = null,
        ),
        ProviderProfile(
            InferenceProvider.OPENROUTER,
            "openrouter/free",
            enabled = false,
            consentVersion = null,
        ),
    )
}
```

Afirmar que no existe método público para guardar otro `modelId`, que OpenRouter siempre termina en `/free` y que el orden es estable.

- [ ] **Step 2: Definir almacenamiento**

```kotlin
interface ProviderCredentialStore {
    fun hasCredential(provider: InferenceProvider): Boolean
    fun saveCredential(provider: InferenceProvider, value: CharArray)
    fun readCredential(provider: InferenceProvider): CharArray?
    fun clearCredential(provider: InferenceProvider)
}
```

`ProviderSettingsStore` persiste enabled, consentimiento y orden, pero no secretos.

- [ ] **Step 3: Implementar Android Keystore**

Crear una clave AES/GCM no exportable por proveedor. Guardar nonce y ciphertext con `AtomicFile` dentro de `noBackupFilesDir`. Sobrescribir buffers `CharArray` después del uso. No incluir claves en mensajes, excepciones o logs.

- [ ] **Step 4: Excluir credenciales de backups**

Excluir `provider-credential-*` en ambas reglas de extracción y confirmar que los archivos viven en `noBackupFilesDir`.

- [ ] **Step 5: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*FreeProviderCatalogTest' --tests '*ProviderSettingsStoreTest' assembleDebugAndroidTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/semantic app/src/test/java/com/capo/diarioclase/processing/semantic app/src/androidTest/java/com/capo/diarioclase/processing/semantic app/src/main/res/xml
git commit -m "feat: configure encrypted free inference providers"
```

---

### Task 3: Paquetes contextuales mínimos

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/InterpretationPacketBuilder.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/InterpretationPacketBuilderTest.kt`

**Interfaces:**
- Consumes: `List<TranscriptSpan>`.
- Produces: `fun build(spans): List<InterpretationRequest>`.

- [ ] **Step 1: Escribir pruebas**

Probar orden por bloque y tiempo, corte a 12.000 caracteres, preferencia por pausas de 4.000 ms, dos spans de contexto, ids públicos `B2-S17` y ausencia de ids de sesión, segmentos, rutas o archivos.

- [ ] **Step 2: Ejecutar rojo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*InterpretationPacketBuilderTest'
```

Expected: FAIL por clase inexistente.

- [ ] **Step 3: Implementar constructor**

```kotlin
class InterpretationPacketBuilder(
    private val maxCharacters: Int = 12_000,
    private val preferredPauseMs: Long = 4_000,
    private val overlapSpans: Int = 2,
    private val promptVersion: String = "free-ele-v1",
    private val schemaVersion: String = "claims-v1",
) {
    fun build(spans: List<TranscriptSpan>): List<InterpretationRequest>
}
```

Calcular `packetId` mediante SHA-256 de versiones y representación textual exacta. No implementar detección semántica previa de candidatos.

- [ ] **Step 4: Ejecutar verde**

Run:

```bash
./gradlew testDebugUnitTest --tests '*InterpretationPacketBuilderTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/semantic/InterpretationPacketBuilder.kt app/src/test/java/com/capo/diarioclase/processing/semantic/InterpretationPacketBuilderTest.kt
git commit -m "feat: build compact contextual interpretation packets"
```

---

### Task 4: Prompt común y tres adaptadores HTTP

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/InterpretationPromptFactory.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/InferenceHttpTransport.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/GeminiProviderClient.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/OpenAiCompatibleProviderClient.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/InterpretationPromptFactoryTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/GeminiProviderClientTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/OpenAiCompatibleProviderClientTest.kt`

**Interfaces:**
- Consumes: request, perfil y credencial.
- Produces: tres implementaciones lógicas mediante dos clientes HTTP.

- [ ] **Step 1: Agregar dependencias y permisos**

Agregar `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3` sin plugin de serialización. Agregar:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

- [ ] **Step 2: Escribir pruebas del prompt**

Afirmar categorías, estados, evidencia obligatoria, prohibición de inventar, manejo de preguntas, citas, planes, autocorrecciones y `contextOnly`. El modo no aparece en el prompt.

- [ ] **Step 3: Implementar prompt y esquema**

`InterpretationPromptFactory.create(request)` devuelve system instruction, user text y JSON Schema lógico compartido. Todos los campos son obligatorios y `additionalProperties` es falso.

- [ ] **Step 4: Definir transporte**

```kotlin
interface InferenceHttpTransport {
    suspend fun request(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int = 30_000,
        readTimeoutMs: Int = 90_000,
    ): HttpTransportResult
}

data class HttpTransportResult(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)
```

La implementación usa `HttpsURLConnection`, UTF-8 y cierre seguro de streams.

- [ ] **Step 5: Implementar Gemini**

Endpoint:

```text
https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent
```

Enviar `x-goog-api-key`, nunca query string. Solicitar `application/json` con esquema. Extraer JSON del primer candidato.

- [ ] **Step 6: Implementar cliente compatible con OpenAI**

```kotlin
data class OpenAiCompatibleProfile(
    val provider: InferenceProvider,
    val endpoint: String,
    val modelId: String,
    val strictJsonSchema: Boolean,
)
```

Perfiles fijos:

```kotlin
OpenAiCompatibleProfile(
    InferenceProvider.GROQ,
    "https://api.groq.com/openai/v1/chat/completions",
    "openai/gpt-oss-20b",
    strictJsonSchema = true,
)

OpenAiCompatibleProfile(
    InferenceProvider.OPENROUTER,
    "https://openrouter.ai/api/v1/chat/completions",
    "openrouter/free",
    strictJsonSchema = false,
)
```

Groq usa `response_format.type = json_schema`, `strict = true`. OpenRouter pide JSON por prompt y, cuando sea admitido, `response_format.type = json_object`; la validación local sigue siendo obligatoria.

- [ ] **Step 7: Mapear errores uniformemente**

401/403 a `AUTHENTICATION`; 429 a `QUOTA`; 500/503 a `SERVER_UNAVAILABLE`; timeout a `TIMEOUT`; cuerpo vacío a `EMPTY_RESPONSE`. Leer `Retry-After` sin superar 5 segundos. Ningún error incluye request body ni credencial.

- [ ] **Step 8: Ejecutar pruebas con transporte falso**

Run:

```bash
./gradlew testDebugUnitTest --tests '*InterpretationPromptFactoryTest' --tests '*GeminiProviderClientTest' --tests '*OpenAiCompatibleProviderClientTest'
```

Expected: PASS sin red real.

- [ ] **Step 9: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/capo/diarioclase/processing/semantic app/src/test/java/com/capo/diarioclase/processing/semantic
git commit -m "feat: add Gemini Groq and OpenRouter adapters"
```

---

### Task 5: Validador común, reducción y procedencia

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/SemanticResponseValidator.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/SemanticClaimReducer.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/SemanticResponseValidatorTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/SemanticClaimReducerTest.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/evidence/EvidenceModels.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/evidence/InterpretationProjector.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/evidence/InterpretationProjectorTest.kt`

**Interfaces:**
- Consumes: JSON crudo, proveedor y spans públicos.
- Produces: claims locales o error de validación.

- [ ] **Step 1: Ampliar origen**

Agregar `GEMINI`, `GROQ` y `OPENROUTER` a `ClaimOrigin`, manteniendo `LOCAL_RULE`, `MANUAL_MARKER` y `USER_EDIT`.

- [ ] **Step 2: Escribir pruebas adversariales**

Rechazar enums desconocidos, confianza fuera de rango, value vacío o mayor a 300 caracteres, más de 100 claims, ids inexistentes, evidencia solo contextual, referencias numéricas sin soporte y JSON truncado.

- [ ] **Step 3: Implementar validación**

```kotlin
sealed interface ValidationOutcome {
    data class Valid(val claims: List<RawClaim>) : ValidationOutcome
    data class Invalid(val reason: ValidationFailure) : ValidationOutcome
}
```

Construir `EvidenceRef` desde spans locales y asignar origen según proveedor. Nunca aceptar evidencia textual devuelta por el modelo.

- [ ] **Step 4: Escribir pruebas del reductor**

Cubrir solapamiento, misma página repetida, ejercicio corregido, ejercicio movido a tarea, cancelación posterior y conflicto ambiguo.

- [ ] **Step 5: Implementar reducción**

Procesar claims por bloque y tiempo. Conservar reemplazados inactivos, afectar solo el elemento referido y usar `UNCERTAIN` cuando haya dos antecedentes posibles.

- [ ] **Step 6: Verificar modos locales**

Proyectar los mismos claims en `CONSERVATIVE`, `BALANCED` y `EXHAUSTIVE` sin usar clientes.

- [ ] **Step 7: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*SemanticResponseValidatorTest' --tests '*SemanticClaimReducerTest' --tests '*InterpretationProjectorTest'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing app/src/test/java/com/capo/diarioclase/processing
git commit -m "feat: validate multi-provider claims with local evidence"
```

---

### Task 6: Caché por paquete y proveedor

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/RoomInterpretationCache.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/RoomInterpretationCacheTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`

**Interfaces:**
- Consumes: paquete, proveedor, modelo, versiones y JSON validado.
- Produces: respuestas reutilizables sin inferencia.

- [ ] **Step 1: Escribir pruebas rojas**

Probar acierto por paquete idéntico, invalidación al cambiar texto, prompt o esquema, conservación entre reaperturas y borrado solo tras aprobar.

- [ ] **Step 2: Agregar entidad**

```kotlin
@Entity(
    tableName = "interpretation_cache",
    indices = [Index("sessionId"), Index("packetId")],
)
data class InterpretationCacheEntity(
    @PrimaryKey val cacheId: String,
    val packetId: String,
    val sessionId: String,
    val provider: String,
    val modelId: String,
    val promptVersion: String,
    val schemaVersion: String,
    val validatedJson: String,
    val createdAtEpochMs: Long,
)
```

`cacheId` es SHA-256 de paquete, proveedor, modelo, prompt y esquema.

- [ ] **Step 3: Migrar Room 4→5**

Crear tabla e índices sin tocar sesiones, audio, transcript, checkpoints, evidencia ni borradores.

- [ ] **Step 4: Implementar búsqueda transversal**

Antes de consultar la red, buscar cualquier caché válido del paquete para proveedores actualmente habilitados, respetando el orden. Una respuesta Gemini previa evita consultar Groq y OpenRouter.

- [ ] **Step 5: Incorporar limpieza**

Sumar caché a conteo y borrado temporal. Ante fallo de limpieza conservar todo.

- [ ] **Step 6: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*RoomInterpretationCacheTest' --tests '*FullJourneyTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/data app/src/main/java/com/capo/diarioclase/processing/semantic app/src/test/java/com/capo/diarioclase
git commit -m "feat: cache validated free provider responses"
```

---

### Task 7: Router secuencial y fallback local

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/FreeInferenceRouter.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/ProviderRetryPolicy.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/semantic/FallbackClaimExtractor.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/FreeInferenceRouterTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/semantic/ProviderRetryPolicyTest.kt`

**Interfaces:**
- Consumes: orden, clientes, credenciales, caché, validador y paquete.
- Produces: `RoutedPacketOutcome`.

- [ ] **Step 1: Escribir matriz de rutas**

Probar:

- Gemini éxito: una llamada total.
- Gemini 429 y Groq éxito.
- Gemini 503 dos veces y Groq éxito.
- Gemini y Groq 429, OpenRouter éxito.
- respuesta inválida, reparación inválida y proveedor siguiente.
- claves ausentes saltan proveedor.
- 401 deshabilita proveedor durante la ejecución.
- todos fallan y se ejecuta local.
- nunca se hacen llamadas paralelas.
- nunca aparece un proveedor no incluido en el catálogo.

- [ ] **Step 2: Definir resultado**

```kotlin
sealed interface RoutedPacketOutcome {
    data class Remote(
        val claims: List<RawClaim>,
        val provider: InferenceProvider,
        val modelId: String,
    ) : RoutedPacketOutcome

    data class Local(
        val claims: List<RawClaim>,
        val failures: List<ProviderFailure>,
    ) : RoutedPacketOutcome
}
```

- [ ] **Step 3: Implementar política de reintento**

`QUOTA` y `AUTHENTICATION` no reintentan. `SERVER_UNAVAILABLE` y `TIMEOUT` reintentan una vez después de 2.000 ms. `INVALID_RESPONSE` permite una única solicitud correctiva que agrega el error de validación al prompt sin incluir datos nuevos.

- [ ] **Step 4: Implementar router**

Iterar perfiles habilitados en orden. Leer y borrar de memoria la clave alrededor de cada llamada. Validar antes de guardar caché. Detenerse en el primer éxito. Acumular fallos sin cuerpos HTTP ni secretos.

- [ ] **Step 5: Implementar fallback**

Encapsular las reglas existentes en `FallbackClaimExtractor`. Los claims usan `LOCAL_RULE` y, cuando completan un paquete remoto fallido dentro de una ficha mixta, quedan por confirmar.

- [ ] **Step 6: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*FreeInferenceRouterTest' --tests '*ProviderRetryPolicyTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/semantic app/src/test/java/com/capo/diarioclase/processing/semantic
git commit -m "feat: route inference across free providers"
```

---

### Task 8: Integración con procesamiento y configuración visible

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionCoordinator.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionWorker.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureUiState.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureViewModel.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureScreen.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/work/TranscriptionCoordinatorTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/ui/capture/CaptureViewModelTest.kt`

**Interfaces:**
- Consumes: transcript completo y configuración de proveedores.
- Produces: ficha con origen remoto, mixto o local.

- [ ] **Step 1: Escribir pruebas de integración**

Afirmar que el router comienza solo después de completar Whisper, cambiar de modo no llama a la red, campos editados sobreviven, reapertura usa caché y cancelación conserva datos.

- [ ] **Step 2: Integrar router**

Reemplazar extracción directa por construcción de paquetes, routing, reducción y proyección. No modificar motor Whisper, ventanas, deduplicación ni checkpoints.

- [ ] **Step 3: Componer dependencias**

`DiarioClaseApp` crea catálogo, stores, transporte, dos tipos de cliente, validador, caché, reductor, router y fallback.

- [ ] **Step 4: Crear configuración compacta**

Para cada proveedor mostrar:

- activar;
- explicación de envío de texto;
- consentimiento;
- campo de clave oculto;
- guardar;
- probar;
- borrar;
- modelo fijo gratuito;
- últimos cuatro caracteres.

No permitir escribir un id de modelo.

- [ ] **Step 5: Probar conexión sin transcripción**

Enviar un prompt fijo mínimo y descartar la respuesta. Para OpenRouter consultar además `GET /api/v1/key` y mostrar advertencia si `is_free_tier` es falso o existe capacidad de gasto sin límite explícito.

- [ ] **Step 6: Mostrar ejecución**

Mostrar proveedor actual, paquete actual, fallbacks configurados y procedencia final `GEMINI`, `GROQ`, `OPENROUTER`, `MIXTO` o `LOCAL`. Ofrecer `REINTENTAR INFERENCIA` y `CONTINUAR CON FICHA LOCAL`.

- [ ] **Step 7: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*TranscriptionCoordinatorTest' --tests '*CaptureViewModelTest' --tests '*InferenceContractTest'
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase app/src/test/java/com/capo/diarioclase
git commit -m "feat: expose free inference fallback chain"
```

---

### Task 9: Feedback, validación integral y APK

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`
- Create: `PHASE5_FREE_INFERENCE_DEVICE_TEST.md`
- Create: `PHASE5_HANDOFF.md`
- Modify: `AGENTS.md`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: borrador generado, edición aprobada y cadena completa.
- Produces: feedback local, versión `0.5.0-free-router`, continuidad, CI verde y APK.

- [ ] **Step 1: Agregar feedback local**

Crear migración Room 5→6 y una entidad que guarde campos generados, campos aprobados, origen e ids de claims. No guardar audio, transcript completo ni claves. El feedback no modifica automáticamente reglas.

- [ ] **Step 2: Escribir prueba física**

El protocolo debe probar:

1. Gemini exitoso.
2. Gemini simulado en 429 y Groq exitoso.
3. Gemini y Groq simulados en 429 y OpenRouter exitoso.
4. todos simulados en fallo y fallback local.
5. modo avión.
6. cambio de modo sin llamadas.
7. cierre y reapertura con caché.
8. edición, aprobación y limpieza.
9. clave inválida sin exposición.

Los fallos simulados se habilitan solo en debug y nunca aceptan claves o cuerpos arbitrarios.

- [ ] **Step 3: Actualizar versión**

Cambiar `versionCode` de 6 a 7 y `versionName` a `0.5.0-free-router`.

- [ ] **Step 4: Ejecutar verificación completa**

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
```

Expected: exit 0.

- [ ] **Step 5: Verificar APK, permisos y secretos**

Run:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'ggml-base.bin|lib/arm64-v8a/libdiarioclase_whisper.so'
apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk | rg 'android.permission.INTERNET|android.permission.ACCESS_NETWORK_STATE'
rg -n --hidden --glob '!build/**' --glob '!.git/**' 'AIza|gsk_|sk-or-v1-' .
```

Expected: modelo y biblioteca presentes; dos permisos de red presentes; ninguna credencial real encontrada.

- [ ] **Step 6: Actualizar continuidad**

`PHASE5_HANDOFF.md` registra commit y workflow por tarea, proveedor probado, fallos, prueba física y próximo paso. `AGENTS.md` exige leer diseño, plan y handoff antes de modificar Fase 5.

- [ ] **Step 7: Commit y CI**

```bash
git add app/src/main/java/com/capo/diarioclase app/src/test/java/com/capo/diarioclase app/src/androidTest/java/com/capo/diarioclase PHASE5_FREE_INFERENCE_DEVICE_TEST.md PHASE5_HANDOFF.md AGENTS.md app/build.gradle.kts
git commit -m "release: prepare free inference router APK"
```

Esperar GitHub Actions verde antes de entregar el APK.

- [ ] **Step 8: Cierre empírico**

Instalar el APK exacto de la CI en Moto g max y completar el protocolo. Cada fallo semántico se convierte en un escenario sintético mínimo; no se exige audio ni corpus.

## Límites conscientes

- El router aumenta disponibilidad, no garantiza que un proveedor gratuito nunca cambie sus cuotas.
- “Sin costo” depende de usar cuentas sin facturación y modelos del catálogo bloqueado.
- OpenRouter puede cambiar el modelo que atiende `openrouter/free`; por eso siempre queda último y requiere validación local.
- No se agregará un cuarto proveedor hasta que la prueba física demuestre que los tres remotos más el fallback local son insuficientes.

# Offline Contextual Interpretation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reemplazar la extracción literal por una interpretación contextual offline que reconozca acciones pedagógicas, páginas, ejercicios, tarea, negaciones y autocorrecciones sin necesitar un corpus inicial de grabaciones reales.

**Architecture:** La transcripción original se conserva y se transforma mediante una tubería de componentes puros: agrupación contextual, anotación conversacional, análisis de referencias, extracción de eventos, reducción temporal y puntuación de confianza. La suite sintética declarativa define el comportamiento inicial; los marcadores y las ediciones futuras aportan evidencia opcional sin entrenar modelos ni bloquear la primera versión.

**Tech Stack:** Kotlin 2.x, Android SDK 35, Jetpack Compose, Room, WorkManager, JUnit 4, Robolectric, whisper.cpp existente.

**Spec:** `docs/superpowers/specs/2026-09-14-contextual-interpretation-design.md`

## Global Constraints

- Android y español fijo, orientado al habla rioplatense.
- Todo el procesamiento continúa local y sin permiso `android.permission.INTERNET`.
- No agregar nube, APIs pagas, LLM, clasificadores entrenados ni modelos descargables.
- No modificar el motor Whisper `base`, sus ventanas ni sus checkpoints en esta fase.
- La transcripción original y sus timestamps son inmutables.
- `CONSERVATIVE` continúa como modo predeterminado.
- Cambiar de modo reinterpreta claims persistidos y nunca retranscribe audio.
- Los campos editados por el usuario no se sobrescriben.
- Ningún claim visible puede carecer de evidencia textual y temporal.
- El audio se elimina únicamente después de aprobación explícita y guardado verificado.
- Cada tarea termina subida a `feature/phase5-contextual-interpretation` y validada por GitHub Actions antes de continuar.

---

### Task 1: Matriz sintética y contrato del intérprete

**Files:**
- Create: `app/src/test/java/com/capo/diarioclase/processing/evidence/SyntheticInterpretationScenarios.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/evidence/ContextualInterpreterContractTest.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/ContextualInterpreter.kt`

**Interfaces:**
- Consumes: `List<TranscriptSpan>`, `List<InterpretationMarker>` y `InterpretationMode`.
- Produces: `fun interpret(spans: List<TranscriptSpan>, markers: List<InterpretationMarker> = emptyList(), mode: InterpretationMode): InterpretationResult`.

- [ ] **Step 1: Crear el contrato mínimo de tipos**

Crear `ContextualInterpreter.kt` con:

```kotlin
data class InterpretationMarker(
    val category: ClaimCategory,
    val blockId: BlockId,
    val atMs: Long,
)

data class InterpretationResult(
    val claims: List<EvidenceClaim>,
    val presentation: ClaimPresentation,
)

fun interface ContextualInterpreter {
    fun interpret(
        spans: List<TranscriptSpan>,
        markers: List<InterpretationMarker>,
        mode: InterpretationMode,
    ): InterpretationResult
}
```

- [ ] **Step 2: Crear escenarios declarativos sintéticos**

En `SyntheticInterpretationScenarios.kt`, definir:

```kotlin
data class ExpectedClaim(
    val category: ClaimCategory,
    val value: String,
    val status: ClaimStatus,
)

data class InterpretationScenario(
    val name: String,
    val lines: List<String>,
    val expectedActive: Set<ExpectedClaim>,
    val forbiddenValues: Set<String> = emptySet(),
)

object SyntheticInterpretationScenarios {
    val core = listOf(
        InterpretationScenario(
            "pagina y ejercicio distribuidos",
            listOf("Vamos a la página cuarenta y dos", "Hacemos el ejercicio tres"),
            setOf(
                ExpectedClaim(ClaimCategory.PAGE, "42", ClaimStatus.PERFORMED),
                ExpectedClaim(ClaimCategory.EXERCISE, "3 (p. 42)", ClaimStatus.PERFORMED),
            ),
        ),
        InterpretationScenario(
            "autocorreccion mueve ejercicio a tarea",
            listOf(
                "Hacemos el tres y el cuatro de la página cuarenta y dos",
                "El cuatro no, perdón, queda para casa",
            ),
            setOf(
                ExpectedClaim(ClaimCategory.EXERCISE, "3 (p. 42)", ClaimStatus.PERFORMED),
                ExpectedClaim(ClaimCategory.HOMEWORK, "4 (p. 42)", ClaimStatus.ASSIGNED),
            ),
            forbiddenValues = setOf("4 (p. 42):PERFORMED"),
        ),
        InterpretationScenario(
            "pregunta no afirma realizacion",
            listOf("¿Hicieron el ejercicio cinco de tarea?"),
            emptySet(),
            forbiddenValues = setOf("5", "5:ASSIGNED", "5:PERFORMED"),
        ),
        InterpretationScenario(
            "plan futuro no cuenta como realizado",
            listOf("La semana que viene vemos el subjuntivo"),
            emptySet(),
            forbiddenValues = setOf("subjuntivo"),
        ),
    )
}
```

Agregar además casos explícitos para cifras y palabras, listas, rangos, sufijos `4a`, cambio de página, “el siguiente”, “ese mismo”, tarea cancelada, cita metalingüística, repetición legítima y duplicado de solapamiento.

- [ ] **Step 3: Agregar generación combinatoria sin corpus**

Crear una función que combine:

```kotlin
val actionVerbs = listOf("hacemos", "resolvemos", "corregimos")
val pageForms = listOf("página 42", "página cuarenta y dos", "la cuarenta y dos")
val exerciseForms = listOf("ejercicio 3", "el tres", "actividad tres")
```

La fábrica debe generar spans y expectativas equivalentes para cada combinación. No usar archivos de audio, transcripciones externas ni datos del usuario.

- [ ] **Step 4: Escribir el contract test fallido**

Construir spans con separación de 1.000 ms y afirmar, para cada escenario, categoría, valor, estado, evidencia no vacía, bloque y timestamps válidos. Afirmar también que ningún `forbiddenValues` aparece entre claims activos.

- [ ] **Step 5: Ejecutar para confirmar rojo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*ContextualInterpreterContractTest'
```

Expected: FAIL porque todavía no existe una implementación contextual.

- [ ] **Step 6: Commit del contrato rojo**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/evidence/ContextualInterpreter.kt app/src/test/java/com/capo/diarioclase/processing/evidence
git commit -m "test: define contextual interpretation contract"
```

---

### Task 2: Ensamblado de unidades contextuales

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/ContextualUtteranceAssembler.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/evidence/ContextualUtteranceAssemblerTest.kt`

**Interfaces:**
- Consumes: `List<TranscriptSpan>`.
- Produces: `fun assemble(spans: List<TranscriptSpan>): List<ContextualUtterance>`.

- [ ] **Step 1: Escribir pruebas de límites**

Probar que:

- spans del mismo bloque separados por hasta 2.500 ms se agrupan;
- el cambio de bloque siempre corta la unidad;
- una unidad se corta al superar 30.000 ms o 600 caracteres;
- el orden de ids, timestamps y textos originales se conserva;
- una pausa larga corta el contexto;
- una lista vacía devuelve una lista vacía.

- [ ] **Step 2: Ejecutar para confirmar rojo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*ContextualUtteranceAssemblerTest'
```

Expected: FAIL por tipos inexistentes.

- [ ] **Step 3: Implementar el modelo y el ensamblador**

```kotlin
data class ContextualUtterance(
    val blockId: BlockId,
    val startMs: Long,
    val endMs: Long,
    val spans: List<TranscriptSpan>,
) {
    val originalText: String = spans.joinToString(" ") { it.text.trim() }
}

class ContextualUtteranceAssembler(
    private val maxGapMs: Long = 2_500,
    private val maxDurationMs: Long = 30_000,
    private val maxCharacters: Int = 600,
) {
    fun assemble(spans: List<TranscriptSpan>): List<ContextualUtterance>
}
```

Ordenar por bloque y tiempo. No fusionar texto dentro de Room ni alterar `TranscriptSpan`.

- [ ] **Step 4: Ejecutar pruebas del ensamblador**

Run:

```bash
./gradlew testDebugUnitTest --tests '*ContextualUtteranceAssemblerTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/evidence/ContextualUtteranceAssembler.kt app/src/test/java/com/capo/diarioclase/processing/evidence/ContextualUtteranceAssemblerTest.kt
git commit -m "feat: assemble transcript spans into contextual utterances"
```

---

### Task 3: Anotación conversacional y autocorrecciones

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/ConversationalNormalizer.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/evidence/ConversationalNormalizerTest.kt`

**Interfaces:**
- Consumes: `ContextualUtterance`.
- Produces: `fun normalize(utterance: ContextualUtterance): List<AnnotatedClause>`.

- [ ] **Step 1: Escribir pruebas de anotación**

Cubrir estas entradas:

```text
"Hacemos el tres, no, perdón, el cuatro"
"El cinco queda para casa. Bah, el cinco no"
"¿Hicieron el ejercicio seis?"
"La semana que viene vemos el subjuntivo"
"La frase dice: hacemos el ejercicio cuatro"
"Repetimos: fui, fuiste, fue"
```

Esperar respectivamente una reparación, una cancelación, una pregunta, un plan futuro, una cita y una repetición pedagógica. Verificar que ninguna palabra desaparece de `originalText`.

- [ ] **Step 2: Ejecutar para confirmar rojo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*ConversationalNormalizerTest'
```

Expected: FAIL por `AnnotatedClause` inexistente.

- [ ] **Step 3: Implementar anotaciones sin limpieza destructiva**

```kotlin
enum class ClauseSignal {
    ASSERTION, QUESTION, FUTURE_PLAN, QUOTE, REPAIR, CANCELLATION
}

data class AnnotatedClause(
    val normalizedText: String,
    val originalText: String,
    val signals: Set<ClauseSignal>,
    val evidence: List<EvidenceRef>,
    val ordinal: Int,
)

class ConversationalNormalizer {
    fun normalize(utterance: ContextualUtterance): List<AnnotatedClause>
}
```

Dividir por puntuación y marcadores discursivos conservando el alcance. Reconocer `no`, `perdón`, `mejor`, `quise decir`, `en realidad`, `bah`, `al final no` y `dejamos`. Un `no` aislado no cancela categorías no relacionadas.

- [ ] **Step 4: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*ConversationalNormalizerTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/evidence/ConversationalNormalizer.kt app/src/test/java/com/capo/diarioclase/processing/evidence/ConversationalNormalizerTest.kt
git commit -m "feat: annotate Spanish classroom speech repairs"
```

---

### Task 4: Analizador de páginas, ejercicios, listas y rangos

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/BookReferenceParser.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/evidence/BookReferenceParserTest.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/evidence/SpanishNumberNormalizer.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/evidence/SpanishNumberNormalizerTest.kt`

**Interfaces:**
- Consumes: `AnnotatedClause` y `ReferenceContext`.
- Produces: `fun parse(clause: AnnotatedClause, context: ReferenceContext): ParsedReferences`.

- [ ] **Step 1: Escribir pruebas de números y referencias**

Cubrir:

- `42`, `cuarenta y dos`, `ciento uno`;
- `3 y 4`, `3, 4 y 6`;
- `del 3 al 7`;
- `4a`, `4 b`, `cuatro ce`;
- `páginas 42 y 43`;
- `el siguiente`, `los dos siguientes`, `ese mismo`;
- cambio de página antes de un nuevo ejercicio;
- elipsis sin antecedente como referencia no resuelta.

- [ ] **Step 2: Ejecutar para confirmar rojo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*BookReferenceParserTest' --tests '*SpanishNumberNormalizerTest'
```

Expected: FAIL para listas, rangos, sufijos y elipsis.

- [ ] **Step 3: Ampliar normalización numérica**

Agregar:

```kotlin
data class ParsedNumberSequence(
    val values: List<String>,
    val consumedCharacters: Int,
)

fun readSequence(text: String): ParsedNumberSequence?
```

Limitar rangos expandidos a 20 elementos para evitar entradas patológicas. Mantener `readPrefix` compatible.

- [ ] **Step 4: Implementar estado de referencias**

```kotlin
data class ReferenceContext(
    val activePages: List<Int> = emptyList(),
    val lastExercises: List<String> = emptyList(),
)

data class ParsedReferences(
    val pages: List<Int>,
    val exercises: List<String>,
    val unresolved: List<String>,
    val nextContext: ReferenceContext,
)

class BookReferenceParser {
    fun parse(
        clause: AnnotatedClause,
        context: ReferenceContext,
    ): ParsedReferences
}
```

La salida debe mantener ejercicios separados para que una corrección pueda afectar solamente uno.

- [ ] **Step 5: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*BookReferenceParserTest' --tests '*SpanishNumberNormalizerTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/evidence/BookReferenceParser.kt app/src/main/java/com/capo/diarioclase/processing/evidence/SpanishNumberNormalizer.kt app/src/test/java/com/capo/diarioclase/processing/evidence
git commit -m "feat: parse contextual page and exercise references"
```

---

### Task 5: Eventos pedagógicos, reducción temporal y confianza

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/PedagogicalEventExtractor.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/ContextualClaimReducer.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/EvidenceConfidenceScorer.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/evidence/RuleBasedContextualInterpreter.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/evidence/PedagogicalEventExtractorTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/evidence/ContextualClaimReducerTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/evidence/EvidenceConfidenceScorerTest.kt`

**Interfaces:**
- Consumes: cláusulas anotadas, referencias analizadas y marcadores opcionales.
- Produces: `List<PedagogicalEvent>`, luego `List<EvidenceClaim>` y finalmente `InterpretationResult`.

- [ ] **Step 1: Escribir pruebas del extractor**

Afirmar que:

- “hicimos” y “corregimos” producen `PERFORMED`;
- “vamos a hacer” produce `PROPOSED`;
- “queda para casa” produce `ASSIGNED`;
- preguntas, citas y planes para otra clase no producen claims activos;
- “no hacemos el cuatro” produce `CANCELLED`;
- una actividad puede existir sin página ni ejercicio;
- un tema requiere un núcleo nominal explícito y no la cola completa de la conversación.

- [ ] **Step 2: Crear el modelo de eventos**

```kotlin
data class PedagogicalEvent(
    val category: ClaimCategory,
    val value: String,
    val normalizedValue: String,
    val status: ClaimStatus,
    val blockId: BlockId,
    val ordinal: Int,
    val evidence: List<EvidenceRef>,
    val signals: Set<ConfidenceSignal>,
)

enum class ConfidenceSignal {
    EXPLICIT_CATEGORY,
    EXPLICIT_ACTION,
    RESOLVED_NUMBER,
    RESOLVED_PAGE,
    SAME_BLOCK_CONTEXT,
    MANUAL_MARKER,
    UNRESOLVED_ELLIPSIS,
    QUESTION,
    QUOTE,
    CONFLICT,
    LOW_TRANSCRIPT_CONFIDENCE,
}
```

- [ ] **Step 3: Implementar `PedagogicalEventExtractor`**

Usar tablas pequeñas de verbos y expresiones. No incorporar una dependencia NLP externa. El extractor debe emitir eventos incluso cancelados o corregidos para conservar trazabilidad.

- [ ] **Step 4: Escribir pruebas rojas del reductor**

Cubrir:

```text
"Hacemos el 3 y el 4" + "El 4 queda para casa"
"Vamos a hacer el 5" + "Al final no"
"Página 30" + "No, perdón, 31" + "Ejercicio 2"
"Hacemos el 3" + repetición idéntica por solapamiento
```

Esperar que prevalezca el evento posterior compatible, que el resto permanezca activo y que la evidencia anterior se conserve inactiva.

- [ ] **Step 5: Implementar reducción temporal**

```kotlin
class ContextualClaimReducer {
    fun reduce(events: List<PedagogicalEvent>): List<EvidenceClaim>
}
```

La clave de identidad es categoría, valor normalizado, página vinculada y bloque. Una corrección solo reemplaza la entidad a la que puede enlazarse. Si hay dos antecedentes posibles, mantener `UNCERTAIN` en vez de elegir silenciosamente.

- [ ] **Step 6: Escribir e implementar pruebas de confianza**

Implementar:

```kotlin
class EvidenceConfidenceScorer {
    fun score(event: PedagogicalEvent): Double
}
```

Puntaje base `0.50`; sumar `0.15` por categoría explícita, `0.15` por acción explícita, `0.10` por número resuelto, `0.05` por página resuelta, `0.10` por marcador manual y `0.05` por contexto del mismo bloque. Restar `0.20` por elipsis no resuelta, `0.30` por conflicto y `0.25` por pregunta o cita. Limitar a `0.0..1.0` y luego limitar por la menor confianza de los spans de evidencia.

- [ ] **Step 7: Componer el intérprete**

`RuleBasedContextualInterpreter` debe ejecutar ensamblador, normalizador, parser, extractor, reductor, scorer y `InterpretationProjector` en ese orden.

- [ ] **Step 8: Ejecutar suite de la tarea y contrato completo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*PedagogicalEventExtractorTest' --tests '*ContextualClaimReducerTest' --tests '*EvidenceConfidenceScorerTest' --tests '*ContextualInterpreterContractTest'
```

Expected: PASS para toda la matriz sintética.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/evidence app/src/test/java/com/capo/diarioclase/processing/evidence
git commit -m "feat: interpret contextual classroom events offline"
```

---

### Task 6: Integración con procesamiento, modos y ficha editable

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionCoordinator.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/work/TranscriptionCoordinatorTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`
- Delete: `app/src/main/java/com/capo/diarioclase/processing/evidence/LiteralClaimExtractor.kt`
- Delete: `app/src/main/java/com/capo/diarioclase/processing/evidence/ClaimReducer.kt`
- Delete: `app/src/test/java/com/capo/diarioclase/processing/evidence/LiteralClaimExtractorTest.kt`

**Interfaces:**
- Consumes: `ContextualInterpreter.interpret`.
- Produces: la misma `DiaryDraft` y persistencia de evidencia que la UI ya consume.

- [ ] **Step 1: Escribir pruebas de integración rojas**

Agregar recorridos que confirmen:

- la interpretación se ejecuta una sola vez al completar todos los segmentos;
- cambiar `CONSERVATIVE` a `BALANCED` no llama a `WindowTranscriptionEngine`;
- un campo con `userEdited = true` conserva exactamente su texto;
- una corrección distribuida entre ventanas termina en un solo resultado activo;
- cada claim aceptado y por confirmar tiene evidencia válida.

- [ ] **Step 2: Ejecutar para confirmar rojo**

Run:

```bash
./gradlew testDebugUnitTest --tests '*TranscriptionCoordinatorTest' --tests '*FullJourneyTest'
```

Expected: FAIL porque el coordinador usa `LiteralClaimExtractor`.

- [ ] **Step 3: Reemplazar dependencias del coordinador**

Inyectar:

```kotlin
private val interpreter: ContextualInterpreter
```

Al completar la transcripción, llamar:

```kotlin
val result = interpreter.interpret(
    spans = store.transcript(sessionId),
    markers = emptyList(),
    mode = mode,
)
```

Construir `DiaryDraft` a partir de `result.presentation.accepted` y mantener sin cambios la protección de campos editados.

- [ ] **Step 4: Componer implementación en `DiarioClaseApp`**

Crear una sola instancia de `RuleBasedContextualInterpreter` con todos sus componentes puros. No modificar la composición de Whisper, WorkManager ni limpieza.

- [ ] **Step 5: Retirar el extractor anterior**

Eliminar las tres clases antiguas solo cuando no queden referencias productivas ni pruebas que dependan de ellas.

- [ ] **Step 6: Ejecutar regresión completa**

Run:

```bash
./gradlew testDebugUnitTest --max-workers=2
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase app/src/test/java/com/capo/diarioclase
git commit -m "refactor: replace literal extraction with contextual interpreter"
```

---

### Task 7: Marcadores temporales opcionales

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/RoomProcessingStore.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionCoordinator.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/work/TranscriptionCoordinatorTest.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureUiState.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureViewModel.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureScreen.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/ui/capture/CaptureViewModelTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`

**Interfaces:**
- Consumes: toques del usuario mientras una sesión está grabando o pausada.
- Produces: `InterpretationMarkerEntity` persistido y convertido a `InterpretationMarker`.

- [ ] **Step 1: Escribir pruebas rojas de persistencia y UI**

Probar que un toque guarda sesión, bloque, categoría y posición temporal; que reabrir la app conserva el marcador; que limpiar después de aprobar lo elimina; y que un marcador no crea un claim si no existe texto compatible alrededor.

- [ ] **Step 2: Crear entidad y migración Room 4→5**

```kotlin
@Entity(
    tableName = "interpretation_markers",
    indices = [Index("sessionId"), Index("blockId")],
)
data class InterpretationMarkerEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val blockId: String,
    val category: String,
    val atMs: Long,
    val createdAtEpochMs: Long,
)
```

La migración crea la tabla y sus índices sin tocar sesiones, audio, transcript, evidencia ni borradores.

- [ ] **Step 3: Agregar DAO y limpieza**

Agregar `insertInterpretationMarker`, `interpretationMarkers(sessionId)`, `deleteInterpretationMarkersForSession` y sumar la tabla a `temporaryRowCount`. Extender `ProcessingStore` para devolver los marcadores de la sesión y reemplazar `markers = emptyList()` en `TranscriptionCoordinator` por `markers = store.interpretationMarkers(sessionId)`. El borrado continúa ocurriendo solamente en el flujo de aprobación verificada.

- [ ] **Step 4: Incorporar cuatro acciones compactas**

Mostrar `TAREA`, `PÁGINA`, `EJERCICIO` y `ACTIVIDAD` durante grabación o pausa. Cada acción guarda el timestamp actual, sin abrir formularios ni interrumpir el audio.

- [ ] **Step 5: Aplicar ventana temporal del marcador**

En el intérprete, un marcador es compatible si está en el mismo bloque y a no más de 20.000 ms antes o 10.000 ms después de la evidencia. Solo agrega `MANUAL_MARKER` al puntaje de una categoría coincidente.

- [ ] **Step 6: Ejecutar pruebas y migración**

Run:

```bash
./gradlew testDebugUnitTest --tests '*CaptureViewModelTest' --tests '*FullJourneyTest'
```

Expected: PASS, incluidos reapertura y limpieza.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/data app/src/main/java/com/capo/diarioclase/processing app/src/main/java/com/capo/diarioclase/ui app/src/test/java/com/capo/diarioclase
git commit -m "feat: add optional temporal interpretation markers"
```

---

### Task 8: Registro local de correcciones futuras

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/Entities.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/SessionDao.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/data/db/DiarioDatabase.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`

**Interfaces:**
- Consumes: borrador generado antes de editar y borrador finalmente aprobado.
- Produces: `InterpretationFeedbackEntity` local, sin modificar automáticamente reglas ni resultados.

- [ ] **Step 1: Escribir pruebas rojas**

Probar que:

- editar y aprobar guarda un registro con antes y después;
- aprobar sin cambios no guarda feedback;
- el registro no contiene audio;
- el feedback no altera la próxima interpretación;
- el feedback permanece después de limpiar los temporales de la sesión.

- [ ] **Step 2: Crear entidad y migración Room 5→6**

```kotlin
@Entity(tableName = "interpretation_feedback")
data class InterpretationFeedbackEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val mode: String,
    val generatedTopics: String,
    val generatedActivities: String,
    val generatedPages: String,
    val generatedExercises: String,
    val generatedHomework: String,
    val approvedTopics: String,
    val approvedActivities: String,
    val approvedPages: String,
    val approvedExercises: String,
    val approvedHomework: String,
    val evidenceClaimIds: String,
    val createdAtEpochMs: Long,
)
```

No incluir rutas de audio ni texto completo de la transcripción.

- [ ] **Step 3: Guardar feedback antes de la limpieza**

En `approveAndClean`, comparar el borrador generado persistido con el aprobado. Si difieren, guardar feedback en la misma transacción que el diario permanente; después continuar con el protocolo de limpieza existente.

- [ ] **Step 4: Ejecutar pruebas**

Run:

```bash
./gradlew testDebugUnitTest --tests '*FullJourneyTest'
```

Expected: PASS y conservación del feedback tras aprobar.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt
git commit -m "feat: retain local interpretation correction feedback"
```

---

### Task 9: Validación integral, documentación y APK de prueba

**Files:**
- Create: `PHASE5_CONTEXTUAL_INTERPRETATION_DEVICE_TEST.md`
- Create: `PHASE5_HANDOFF.md`
- Modify: `AGENTS.md`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: la implementación completa de Tasks 1 a 8.
- Produces: versión `0.5.0-contextual`, documentación de continuidad, CI verde y APK de prueba.

- [ ] **Step 1: Escribir protocolo físico sin corpus previo**

El protocolo debe indicar grabar un único bloque breve que contenga:

```text
Hoy trabajamos el contraste entre perfecto e indefinido.
Vamos a la página cuarenta y dos.
Hacemos los ejercicios tres y cuatro.
El cuatro no, perdón, queda para casa.
La próxima clase vamos a ver los pronombres.
¿Hicieron el ejercicio cinco?
```

Esperar:

- tema: contraste entre perfecto e indefinido;
- actividad: práctica o trabajo del contraste, si la evidencia supera el umbral;
- página: 42;
- ejercicio realizado: 3 de la página 42;
- tarea: 4 de la página 42;
- no registrar pronombres como tema realizado;
- no registrar ejercicio 5.

Incluir una segunda prueba con marcador `TAREA`, cambio de modo y edición manual.

- [ ] **Step 2: Actualizar versión**

Cambiar `versionCode` de 6 a 7 y `versionName` a `0.5.0-contextual`.

- [ ] **Step 3: Ejecutar verificación completa**

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
```

Expected: exit 0.

- [ ] **Step 4: Verificar composición y privacidad del APK**

Run:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'ggml-base.bin|lib/arm64-v8a/libdiarioclase_whisper.so'
apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk | rg 'android.permission.INTERNET'
```

Expected: modelo y biblioteca presentes; la segunda orden no devuelve resultados.

- [ ] **Step 5: Documentar continuidad**

`PHASE5_HANDOFF.md` debe registrar por tarea:

- commit funcional;
- workflow exitoso;
- cambios realizados;
- pruebas pendientes en dispositivo;
- fallos conocidos;
- próximo paso exacto.

Actualizar `AGENTS.md` para que cualquier agente lea primero el diseño, este plan y el handoff de Fase 5, conservando la Fase 4 como base validada.

- [ ] **Step 6: Commit y CI**

```bash
git add PHASE5_CONTEXTUAL_INTERPRETATION_DEVICE_TEST.md PHASE5_HANDOFF.md AGENTS.md app/build.gradle.kts
git commit -m "release: prepare contextual interpretation device build"
```

Esperar el workflow de GitHub Actions y no declarar la fase terminada hasta obtener exit 0.

- [ ] **Step 7: Prueba física y cierre**

Instalar el APK exacto de la CI en el Moto g max, ejecutar ambos recorridos del protocolo y registrar resultados en `PHASE5_HANDOFF.md`. Un resultado parcialmente correcto abre casos sintéticos de regresión en la tarea responsable; no exige entregar grabaciones ni crear un corpus completo.

## Orden de decisión posterior

No incorporar un clasificador o LLM por anticipado. Después de la prueba física:

1. Convertir cada fallo observado en un escenario sintético mínimo y anónimo.
2. Corregir ensamblador, normalizador, parser, extractor o reductor según la causa.
3. Repetir la suite completa y la frase física afectada.
4. Considerar un clasificador local pequeño solamente si diez o más fallos distintos comparten ambigüedad semántica no resoluble mediante contexto y reglas.

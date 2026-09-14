# Traspaso técnico y continuidad

Última actualización: 2026-09-14

Este archivo es el punto de entrada para cualquier agente que retome el desarrollo. Debe actualizarse después de cada bloque funcional validado.

## Resumen ejecutivo

Diario de Clase es una aplicación Android privada y offline para grabar una jornada docente en varios bloques, transcribirla localmente en español y generar una única ficha diaria editable.

La infraestructura de grabación, almacenamiento seguro, extracción estructurada, revisión, archivo y transcripción local con Whisper ya existe. También existe el trabajo persistente con WorkManager. El punto actual de integración es incompleto: la UI todavía ejecuta el coordinador directamente y conserva controles del antiguo modelo de reconocimiento de Android.

La próxima tarea es conectar la UI con `TranscriptionWorkScheduler` y observar el progreso persistido en Room.

## Requisitos de producto que no deben cambiarse

- Plataforma inicial: Android solamente.
- Dispositivo de aceptación: Moto g max con Android 16.
- Una jornada contiene varios bloques que el usuario pausa y retoma.
- Duraciones habituales: 90 minutos, 60 minutos y 50 minutos.
- Debe existir un solo diario por día.
- Cada ficha diaria se guarda y puede copiarse al portapapeles.
- Campos separados:
  - Temas.
  - Actividades realizadas.
  - Páginas.
  - Ejercicios hechos.
  - Tarea.
- Los campos son editables antes de aprobar.
- La interpretación tiene tres modos configurables: conservador, equilibrado y exhaustivo.
- El modo conservador es el predeterminado.
- La transcripción es local, gratuita, privada y fija en español, orientada al habla rioplatense.
- La aplicación no necesita ser multilingüe.
- El audio es temporal. Se conserva durante grabación, transcripción, errores y revisión.
- El audio se elimina únicamente después de guardar la ficha y recibir confirmación explícita del usuario.
- Si falla la transcripción, no se pierde el audio ni el progreso ya confirmado.
- La app no solicita permiso de Internet.
- Estética: minimalista brutalista, moderna, elegante, oscura y sin colores.

## Fuente de verdad remota

| Elemento | Valor |
|---|---|
| Repositorio | `ravacholin/Diarioele` |
| Rama de trabajo | `feature/phase4-whisper-recovery` |
| PR | [#1](https://github.com/ravacholin/Diarioele/pull/1), borrador |
| Base histórica de recuperación | `main@35c45a1` |
| Head documental previo a este archivo | `e079a9b47b09e4bd1e5de92e0378ddfa67ea2090` |
| Último código funcional validado | `a53b32145cb044b8539f05231031658c0ae2d6c6` (Tasks 7, 8 y 9) |
| Última CI completa validada | workflow `#74`, run `34889834345` |
| APK de esa CI | artefacto `DiarioClase-Android-debug`, expira 2026-09-28 |

El commit documental posterior a `b5c42b3` no cambia código. Si hay commits posteriores, verificar su CI antes de considerarlos funcionales.

## Avances completados

| Bloque | Resultado | Evidencia |
|---|---|---|
| Fases 1 a 3 | Grabación por bloques, pausa, recuperación, reproducción de segmentos, fichas editables, archivo y borrado confirmado | Código previo en `main` |
| Infraestructura durable | Rama, PR y workflow capaces de reconstruir el APK | PR #1 |
| Modelo local | Modelo español incorporado, copia privada atómica, validación por tamaño y SHA-256 | `ec499a2`, workflow #29 |
| Audio por ventanas | Lectura WAV, ventanas solapadas y deduplicación temporal | `3e6291e`, workflow #33 |
| Persistencia | Room v4, checkpoints por segmento y ejecución por sesión | `5b24221`, workflow #37 |
| Motor nativo | whisper.cpp v1.9.4 fijado, CMake, JNI y ARM64 | `47ab1b9`, workflow #41 |
| Coordinador | Procesamiento por ventana, confirmación transaccional y reanudación | `2872f49`, workflow #49 |
| Segundo plano | WorkManager único por sesión, foreground, progreso, pausa y timeout | `b5c42b3`, workflow #57 |
| Registro durable | Estado de recuperación actualizado | `e079a9b` |
| UI observable (Task 7) | UI conectada al planificador persistente, progreso observado desde Room y flujo de descarga de idioma retirado | `5b60dd0`, workflow #67 |
| Limpieza segura (Task 8) | La limpieza temporal borra y cuenta `transcription_runs` y `transcription_checkpoints`; se archiva solo con cero temporales | `2824a22`, workflow #70 |
| Composición y entrega (Task 9) | Motor de reconocimiento anterior eliminado, manifiesto sin RecognitionService, versión `0.4.0-whisper`, protocolo de prueba física | `a53b321`, workflow #74 |

## Arquitectura actual

### Grabación

- `RecordingService` mantiene la grabación como foreground service.
- `RecordingRecovery` recupera sesiones interrumpidas.
- `FileSegmentStore` guarda WAV temporales en almacenamiento privado.
- `PersistingSegmentStore` sincroniza archivos con Room.
- Los segmentos pueden reproducirse antes del procesamiento para comprobar que el audio existe.

### Transcripción

- `WhisperModelInstaller` instala y valida el modelo empaquetado.
- `WhisperNativeBridge` comunica Kotlin con whisper.cpp por JNI.
- `WhisperTranscriptionEngine` transcribe PCM en español y propaga cancelación al runtime nativo.
- `AudioWindowing` divide segmentos extensos.
- `TranscriptDeduplicator` evita duplicados causados por solapamiento.
- `TranscriptionCoordinator.processNext` procesa una ventana por vez y confirma resultados en Room.
- `RoomProcessingStore` guarda texto, checkpoints, progreso, estados y fallos.

### Ejecución persistente

- `TranscriptionWorkScheduler` crea un único trabajo `transcription-<sessionId>`.
- `start` y `resume` limpian la pausa persistida antes de encolar.
- `pause` persiste la solicitud antes de cancelar el trabajo.
- `TranscriptionWorker` procesa ventanas hasta completar, pausar o fallar.
- Cada ventana tiene un timeout de 300.000 ms.
- La notificación foreground muestra porcentaje confirmado y acción `PAUSAR`.
- La cancelación alcanza a Whisper nativo.
- `DiarioClaseApp.transcriptionScheduler` es lazy para no romper el arranque ni Robolectric.

### Interpretación y ficha

- `LiteralClaimExtractor`, `ClaimReducer` e `InterpretationProjector` generan evidencia.
- Categorías: tema, actividad, página, ejercicio y tarea.
- Estados de interpretación: realizado, asignado, propuesto, cancelado, corregido e incierto.
- Modos: `CONSERVATIVE`, `BALANCED`, `EXHAUSTIVE`.
- Los elementos dudosos pueden quedar “por confirmar”.
- Los campos editados por el usuario no deben ser sobrescritos por un cambio de modo.

### Aprobación y limpieza

- `CleanupCoordinator` guarda primero el diario permanente.
- La interfaz exige confirmación explícita con `APROBAR Y BORRAR AUDIO`.
- Ante fallo de limpieza, el diario permanece seguro y la limpieza queda pendiente.
- Todavía falta incorporar las tablas nuevas de checkpoints y runs al borrado temporal final. Eso corresponde a Task 8.

## Garantías de seguridad ya cubiertas

- El audio no se borra por un fallo de transcripción.
- Un cierre o cancelación conserva checkpoints y texto confirmado.
- Una ventana confirmada no vuelve a procesarse.
- Texto, checkpoint, progreso y estado se confirman juntos.
- Los fallos conservan resultados anteriores.
- Las migraciones mantienen sesiones y borradores existentes.
- El modelo vive en almacenamiento privado no respaldable.
- El APK no solicita Internet.
- El motor está fijado a español y no realiza detección automática ni traducción.
- La biblioteca nativa solo se compila para `arm64-v8a`.
- La licencia MIT de whisper.cpp está empaquetada.

## Problemas ya encontrados y resueltos

### Error de compilación en el worker

- Se importó inicialmente `android.app.ServiceInfo`.
- El import correcto es `android.content.pm.ServiceInfo`.
- `CoroutineWorker.onStopped()` es final en WorkManager 2.10.1 y no puede sobrescribirse.
- Solución: cancelación mediante la coroutine y `suspendCancellableCoroutine` en `WhisperTranscriptionEngine`.
- Commit: `3db8d36803b66295aa9ad94c03d5b1f6a836f036`.

### Robolectric fallaba al iniciar la aplicación

- `DiarioClaseApp.onCreate()` solicitaba WorkManager de forma ansiosa.
- Los tests no lo habían inicializado.
- Solución: `transcriptionScheduler` lazy.
- Commit funcional: `b5c42b3b59ec7d06c483763b30f398765c5bb3b5`.

### Flujo de idioma que no funcionaba en el teléfono

- La versión anterior dependía de `SpeechRecognizer` y de una descarga de español administrada por Android.
- La descarga no terminaba o no informaba progreso útil.
- Whisper local reemplaza esa dependencia.
- Todavía quedan clases y controles antiguos para eliminar en Task 7 y Task 9.

## Estado de la Fase 4

Tasks 7, 8 y 9 están completas y validadas por GitHub Actions `#74` (`a53b321`):

- **Task 7** (`5b60dd0`, #67): la UI está conectada al procesamiento persistente.
  `AndroidCaptureActions` expone `startProcessing`/`pauseProcessing`/`resumeProcessing`
  delegando en `app.transcriptionScheduler`; `CaptureViewModel` observa
  `Flow<TranscriptionProgress?>` desde Room sin ejecutar inferencia propia;
  `CaptureUiState` expone `processedMs`, `processingTotalMs`, `progressPercent`,
  `progressLabel`, `transcriptionPaused` y `processingFailure`; la pantalla muestra
  progreso determinado, pausa/reanudar/reintentar y "PREPARANDO MODELO", sin
  controles de descarga de idioma.
- **Task 8** (`2824a22`, #70): la limpieza temporal borra y cuenta
  `transcription_runs` y `transcription_checkpoints`; una ficha se archiva solo
  cuando `temporaryRowCount == 0`, y un fallo conserva audio, run y checkpoints.
- **Task 9** (`a53b321`, #74): se eliminaron `AndroidOnDeviceTranscriptionEngine`,
  `RecognitionDeadline` y `SpanishModelSupport` con sus pruebas (incluido
  `AudioPipeConsumptionTest`, que ejercitaba helpers del motor viejo); el manifiesto
  ya no consulta `RecognitionService`; la versión es `0.4.0-whisper` (código 6); el
  APK sigue sin permiso de Internet.

## Próximo paso exacto: prueba física

La Fase 4 está completa en código y validada por CI, pero **no** probada en el
teléfono. El siguiente paso es la validación física en el Moto g max siguiendo
`PHASE4_WHISPER_DEVICE_TEST.md` (10 s → 1 min pausa/reanudar → 10 min
cierre/reapertura → bloque largo). Reglas: desactivar red, no aprobar durante una
prueba de fallo (aprobar borra los temporales), y conservar el audio y registrar
estado, progreso y error si algo falla. Ninguna CI verde reemplaza esta prueba.

## Cómo obtener el APK

El workflow `.github/workflows/android-apk.yml` publica el APK de dos formas:

1. **GitHub Release (recomendado para el teléfono).** El workflow crea un Release
   (marcado *prerelease*) con `app-debug.apk` adjunto como
   `DiarioClase-<tag>-debug.apk`. Descarga directa desde
   `https://github.com/ravacholin/Diarioele/releases`, sin zip ni vencimiento.
   Primer release: **`v0.4.0-whisper`**. Se dispara de dos maneras:
   - **`workflow_dispatch` con input `release_tag`** (la que se usa desde una sesión
     de agente, porque el token de sesión **no** puede pushear tags): ejecutar el
     workflow "Compilar APK Android" sobre la rama con `release_tag=v0.4.x-whisper`.
     La CI crea el tag (en `github.sha`) y el Release del lado del servidor.
   - **Push de un tag `v*`** (si tenés permiso local de push de tags).
2. **Artefacto de Actions.** Cada compilación verde deja el artefacto
   `DiarioClase-Android-debug` (un `.zip` con `app-debug.apk`), con retención de
   14 días.

Instalación: es un APK **debug** (`0.4.0-whisper`, versionCode 6). Habilitar
"instalar apps desconocidas"; si hay una versión previa con otra firma, desinstalar
primero (borra sus datos locales). El APK no pide permiso de Internet.

## Registro de sesión (2026-09-14)

- Retomada la Fase 4 desde `AGENTS.md` en `feature/phase4-whisper-recovery`.
- Task 7 (`5b60dd0`, CI #67), Task 8 (`2824a22`, CI #70) y Task 9 (`a53b321`, CI #74)
  implementadas, validadas por Actions y registradas en los tres documentos de
  continuidad. Docs finales en `35ad661`.
- Task 9 tuvo una falla de CI intermedia (`8a388b2`): al borrar el motor viejo se
  fueron los helpers `writePcmToPipe`/`isAudioPipeDrained` que usaba el androidTest
  `AudioPipeConsumptionTest`; se eliminó ese test (código muerto) en `a53b321`.
- Automatizado el Release por tag y publicado `v0.4.0-whisper`.
- **Pendiente:** prueba física en el Moto g max (arriba). El PR #1 sigue en borrador
  hasta esa validación. No mergear a `main` ni borrar audio sin aprobación explícita
  del usuario.

## Secuencia posterior (Task 8 y Task 9 — ya completadas)

> Nota: Task 8 y Task 9 ya están implementadas y validadas (ver "Estado de la Fase 4"). Se conserva el detalle a continuación como registro histórico.

### Task 8: limpieza segura

- Agregar borrado y conteo de `transcription_checkpoints` y `transcription_runs`.
- Conservar audio, checkpoints y spans ante cualquier fallo.
- Tras aprobación confirmada, verificar cero temporales.
- Confirmar que el diario permanente sea idéntico al borrador aprobado.
- Commit sugerido: `test: preserve Whisper data until verified approval`.

### Task 9: composición y entrega

- Eliminar el motor antiguo:
  - `AndroidOnDeviceTranscriptionEngine.kt`
  - `RecognitionDeadline.kt`
  - `SpanishModelSupport.kt`
  - Sus pruebas asociadas.
- Retirar `RecognitionService` del manifiesto.
- Eliminar códigos de fallo exclusivos de SpeechRecognizer si ya no se usan.
- Ajustar versión final a `0.4.0-whisper`.
- Mantener permisos necesarios para micrófono, notificaciones y foreground services.
- Confirmar ausencia de permiso Internet.
- Crear `PHASE4_WHISPER_DEVICE_TEST.md`.
- Verificar que APK incluya modelo y `libdiarioclase_whisper.so`.
- Commit sugerido: `release: prepare offline Spanish Whisper APK`.

## Protocolo de validación física pendiente

Una compilación verde no prueba rendimiento real en el teléfono. La validación final debe hacerse gradualmente:

1. Instalar el APK.
2. Desactivar Wi-Fi y datos móviles.
3. Grabar 10 segundos mencionando claramente los cinco campos.
4. Procesar y registrar duración, progreso, texto y ficha.
5. Probar pausa y reanudación con una grabación de 1 minuto.
6. Probar cierre y reapertura con una grabación de 10 minutos.
7. Solo después probar un bloque largo.
8. No aprobar una ficha durante una prueba de fallo, porque aprobar elimina los temporales.
9. Si la firma debug impide actualizar, desinstalar la versión anterior, sabiendo que eso elimina sus datos locales.

## Validación completa antes de entregar un APK

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'ggml-base.bin|lib/arm64-v8a/libdiarioclase_whisper.so'
apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk | rg 'android.permission.INTERNET'
```

Resultados esperados:

- Gradle termina con exit 0.
- El modelo y la biblioteca ARM64 aparecen en el APK.
- La búsqueda del permiso Internet termina sin resultados.
- GitHub Actions publica `DiarioClase-Android-debug`.
- La prueba física de 10 segundos funciona antes de declarar terminada la fase.

## Procedimiento de continuidad entre agentes

Al iniciar:

1. Leer `AGENTS.md`, este archivo y `PHASE4_RECOVERY_STATUS.md`.
2. Consultar el head actual de la rama.
3. Verificar la CI asociada al último commit de código.
4. No repetir bloques marcados como validados.
5. Empezar por la primera tarea pendiente.
6. Si el entorno local desapareció, reconstruir desde GitHub.

Al cerrar un bloque:

1. Subir todo el código a la rama.
2. Esperar CI verde.
3. Registrar commit funcional y workflow.
4. Actualizar la tabla de avances.
5. Reescribir “Próximo paso exacto” para el siguiente agente.
6. Informar con precisión qué puede probar el usuario y qué todavía no está conectado.

## Regla de honestidad de estado

Distinguir siempre entre:

- Implementado localmente.
- Subido a GitHub.
- Compilado.
- Validado por tests.
- Validado por GitHub Actions.
- Probado físicamente en el Moto g max.

Ninguno de estos estados implica automáticamente los siguientes.

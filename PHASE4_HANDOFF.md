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
| Último código funcional validado | `b5c42b3b59ec7d06c483763b30f398765c5bb3b5` |
| Última CI completa validada | workflow `#57`, run `34878201879` |
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

## Limitación actual crítica

La infraestructura WorkManager compila y está probada, pero no está conectada completamente a la experiencia visible.

Hoy:

- `AndroidCaptureActions.process` llama a `app.transcriptionCoordinator.process(id, mode)`.
- `CaptureViewModel` espera un `ProcessingOutcome` sincrónico.
- `CaptureUiState` todavía contiene `SpanishModelDownloadState`.
- La UI todavía ofrece el flujo de descarga de idioma de Android.
- `DiarioClaseApp` todavía construye `AndroidOnDeviceTranscriptionEngine`, aunque el coordinador usa `whisperEngine`.

Consecuencia: el APK de workflow #57 valida la infraestructura, pero el usuario todavía no recibe la experiencia final de procesamiento persistente. No describir esta versión como Fase 4 terminada.

## Próximo paso exacto: Task 7

Objetivo: UI observable sin descarga de idioma.

### Archivos principales

- `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureUiState.kt`
- `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureViewModel.kt`
- `app/src/main/java/com/capo/diarioclase/ui/capture/CaptureScreen.kt`
- `app/src/main/java/com/capo/diarioclase/AndroidCaptureActions.kt`
- `app/src/main/java/com/capo/diarioclase/MainActivity.kt`
- `app/src/test/java/com/capo/diarioclase/ui/capture/CaptureViewModelTest.kt`
- `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`

### Contrato esperado

Reemplazar el procesamiento sincrónico de `CaptureActions` por:

```kotlin
fun startProcessing(id: SessionId, mode: InterpretationMode)
suspend fun pauseProcessing(id: SessionId)
fun resumeProcessing(id: SessionId, mode: InterpretationMode)
```

Las implementaciones deben delegar en `app.transcriptionScheduler`.

El ViewModel debe consumir `Flow<TranscriptionProgress?>` y derivar la pantalla desde Room. No debe mantener ni ejecutar un job de inferencia propio.

### Estado visible requerido

Agregar a `CaptureUiState`:

- `processedMs`
- `processingTotalMs`
- `progressPercent`
- `progressLabel`
- `transcriptionPaused`
- `processingFailure`

La pantalla debe mostrar:

- `WHISPER LOCAL · ESPAÑOL`
- `MODELO INTEGRADO`
- Barra de progreso determinada.
- Porcentaje confirmado.
- Tiempo procesado y total.
- Bloque y tramo actuales cuando estén disponibles.
- `PAUSAR PROCESAMIENTO`.
- `RETOMAR` cuando esté pausado.
- `REINTENTAR` ante un fallo recuperable.
- `PREPARANDO MODELO` durante preparación.
- No mostrar tiempo restante estimado.

### Eliminaciones de Task 7

Eliminar de UI, ViewModel y acciones:

- `onRequestModel`
- `requestLanguageModel`
- `SpanishModelDownloadState`
- `modelDownload`
- `modelAllowsProcessing`
- Todos los textos y controles sobre descarga de español administrada por Android.

No eliminar todavía las clases antiguas del motor si eso rompe commits intermedios. Su eliminación definitiva corresponde a Task 9.

### Pruebas mínimas de Task 7

- El progreso persistido se refleja en porcentaje y etiqueta.
- Pausar no elimina `lastRecording`.
- Cerrar o recrear el ViewModel no reinicia desde cero.
- Reanudar usa el mismo `sessionId`.
- Un fallo muestra explicación y conserva el audio.
- Al completar aparece la ficha editable.
- No queda ninguna expectativa de descarga de idioma en tests de UI.

Ejecutar:

```bash
./gradlew testDebugUnitTest --tests '*CaptureViewModelTest' --tests '*FullJourneyTest'
./gradlew lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
```

Commit sugerido:

```text
feat: show persistent Whisper progress and controls
```

Después del push, esperar GitHub Actions. Solo si queda verde, actualizar este archivo y `PHASE4_RECOVERY_STATUS.md`.

## Secuencia posterior

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

# Seguimiento de avances

Documento vivo de coordinación. Registra el estado real del proyecto contra los
planes aprobados (`PHASE3_IMPLEMENTATION_PLAN.md`, `PHASE4_WHISPER_LOCAL_IMPLEMENTATION_PLAN.md`)
para que varios agentes puedan avanzar en paralelo sin pisarse.

- **Actualizado:** 2026-09-14
- **Aplicación:** `com.capo.diarioclase.phase2` — Android único, `minSdk = 26`, `targetSdk = 35`.
- **Versión empaquetada actual:** `versionCode = 4`, `versionName = "0.3.0-phase3"` (`app/build.gradle.kts`).
- **Dispositivo de aceptación:** Moto g max con Android 16.
- **Regla transversal:** los Markdown viven en la raíz; no se crea `docs/`. La app no pide `android.permission.INTERNET`.

> Cómo mantener este archivo: cada agente que termina un paso marca su casilla,
> agrega el hash del commit y una línea en la bitácora del final. No borres el
> historial; solo agregá. Si un plan y este documento se contradicen, gana el plan;
> corregí acá.

---

## Panorama por fases

| Fase | Alcance | Estado | Referencia |
| --- | --- | --- | --- |
| Fase 1-2 | App base, grabación PCM, almacenamiento | Completa | `c76be1e`, `8745159`, `99d4b6a` |
| Fase 3 | Cierre seguro del diario (guardar, verificar, limpiar) | Completa en código | `a819984` + fixes de CI/tests |
| Interino | Motor `SpeechRecognizer` con modelo español del sistema | Completo (motor activo actual) | `c45d0f4` … `002b0d5` |
| Fase 4 | Whisper local en español (reemplaza al interino) | Planificada, sin código | `a0b486b` (diseño), `35c45a1` (plan) |

El motor interino (`SpeechRecognizer` + descarga del paquete de español del sistema)
es lo que corre hoy. La Fase 4 lo reemplaza por completo por `whisper.cpp` embebido
y elimina de la UI todo el flujo de "descargar/comprobar español" (ver Tarea 7 y 9).

---

## Fase 3 — Cierre seguro del diario · COMPLETA

Código presente y cubierto por pruebas. Piezas clave:

- `data/repository/RoomDiaryRepository.kt` — guarda la ficha permanente y relee para verificar.
- `diary/cleanup/CleanupCoordinator.kt` — único componente que aprueba y borra temporales; idempotente, `CLEANUP_PENDING` ante fallo parcial.
- `processing/evidence/` — `LiteralClaimExtractor`, `ClaimReducer`, `InterpretationProjector` (modos Conservador/Equilibrado/Exhaustivo).
- `ui/archive/` — archivo local, búsqueda, edición, borrado, copia al portapapeles.
- Migración Room `MIGRATION_2_3` registrada; `DiarioMigrationTest` verde.

Guía empírica: `PHASE3_DEVICE_TEST.md`. Las casillas `- [ ]` del plan de Fase 3
quedaron sin tildar en su archivo, pero el código y las pruebas están presentes;
tratamos la Fase 3 como cerrada salvo que una prueba física la reabra.

---

## Fase 4 — Whisper local en español · TABLERO DE TAREAS

Todas las tareas del plan están **pendientes** (ningún archivo de Fase 4 existe aún:
sin `scripts/`, sin `app/src/main/cpp/`, sin `app/src/main/assets/`). El plan declara
rama destino `main`; respetá eso al integrar el código de implementación.

Leyenda de estado: `pendiente` · `en curso (@agente)` · `hecho (hash)`.

| # | Tarea | Estado | Depende de | Puede ir en paralelo | Requiere |
| --- | --- | --- | --- | --- | --- |
| 1 | Cadena verificable del modelo (script SHA-256, gitignore, gradle, CI) | pendiente | — | sí | red para bajar 147 MB del modelo |
| 2 | Ventanas WAV y deduplicación temporal | pendiente | — | sí | solo JVM |
| 3 | Checkpoints y migración Room 3→4 | pendiente | — | sí | solo JVM/Robolectric |
| 4 | Motor nativo Whisper bloqueado a `es` (JNI + CMake) | pendiente | 1, 2 | no | NDK + `whisper.cpp` `927cfce…` |
| 5 | Coordinador reanudable por ventana | pendiente | 2, 3, 4 | no | — |
| 6 | Trabajo persistente (WorkManager), pausa y progreso | pendiente | 5 | no | — |
| 7 | UI observable, sin descarga de idioma | pendiente | 6 | no | — |
| 8 | Limpieza segura de los nuevos temporales | pendiente | 3, 5 | parcial | — |
| 9 | Composición, versión `0.4.0-whisper`, CI, borrado del motor viejo | pendiente | 1-8 | no | — |

### Orden sugerido para trabajo multi-agente

1. **Arranque en paralelo (sin colisiones):** Tareas **1**, **2** y **3** tocan
   archivos disjuntos y no dependen entre sí. Tres agentes pueden tomarlas a la vez.
2. **Núcleo secuencial:** **4 → 5 → 6 → 7**. Cada una necesita la interfaz que
   define la anterior.
3. **En cuanto 3 y 5 estén:** la Tarea **8** (limpieza) puede avanzar mientras 6/7
   siguen, porque toca `SessionDao` + `CleanupCoordinator`.
4. **Cierre:** la Tarea **9** integra todo, sube la versión, ajusta CI y borra el
   motor `SpeechRecognizer`. Va última y por un solo agente.

### Archivos de la Fase 4 (para evitar pisarse)

- Tarea 1: `scripts/prepare-whisper-model.sh`, `.gitignore`, `.github/workflows/android-apk.yml`, `app/build.gradle.kts`, `app/src/main/assets/models/.gitkeep`.
- Tarea 2: `processing/transcription/AudioWindowing.kt`, `TranscriptDeduplicator.kt` (+ tests).
- Tarea 3: `data/db/Entities.kt`, `DiarioDatabase.kt`, `SessionDao.kt`, `DiarioClaseApp.kt` (+ tests de migración y DAO).
- Tarea 4: `app/src/main/cpp/**`, `processing/transcription/WhisperNativeBridge.kt`, `WhisperModelInstaller.kt`, `WhisperTranscriptionEngine.kt`, `app/build.gradle.kts`.
- Tarea 5: `processing/work/TranscriptionCoordinator.kt`, `RoomProcessingStore.kt`, `processing/transcription/TranscriptionEngine.kt`.
- Tarea 6: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `processing/work/TranscriptionWorker.kt`, `TranscriptionWorkScheduler.kt`, `AndroidManifest.xml`.
- Tarea 7: `ui/capture/**`, `AndroidCaptureActions.kt`, `MainActivity.kt`.
- Tarea 8: `data/db/SessionDao.kt`, `diary/cleanup/CleanupCoordinator.kt`.
- Tarea 9: `DiarioClaseApp.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, CI; **borra** `AndroidOnDeviceTranscriptionEngine.kt`, `RecognitionDeadline.kt`, `SpanishModelSupport.kt` y sus pruebas.

> `SessionDao.kt` lo tocan las tareas 3 y 8. Coordiná: que la 3 lo deje en su forma
> final de checkpoints antes de que la 8 agregue las consultas de borrado, o resolvé
> el merge a mano.

### Constantes fijas del modelo (no reinventar)

- Modelo: `ggml-base.bin`, revisión HF `5359861c739e955e79d9a303bcbc70fb988958b1`.
- Tamaño: `147951465` bytes · SHA-256: `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`.
- Motor: `whisper.cpp` v1.9.4, commit `927cfce34f31707e17f2bff35c349632fb9e2c3a`.
- ABI única: `arm64-v8a`. Idioma fijo `es`, tarea `transcribe`, sin detección ni traducción.
- Ventanas de 30.000 ms, solapamiento 2.000 ms, timeout 300.000 ms por ventana.

---

## Estado de CI

`.github/workflows/android-apk.yml` corre en `push` a `android-phase3` y `main`, y por
`workflow_dispatch`. Ejecuta `testDebugUnitTest` (fallos expuestos), y en la Fase 4
deberá además preparar el modelo verificado antes de empaquetar (Tarea 1, paso 4) y
publicar el artefacto `DiarioClase-Android-debug` (Tarea 9). El release gate del plan
exige run verde sobre `main` + prueba física corta en el Moto g max antes de entregar.

---

## Release gate de la Fase 4 (no entregar el APK solo porque compile)

1. Run exitoso del workflow sobre `main`.
2. Descargar el artefacto exacto de ese run y verificar ZIP + SHA-256 del APK.
3. Confirmar que el APK trae `ggml-base.bin` y `lib/arm64-v8a/libdiarioclase_whisper.so`, y que **no** declara permiso de Internet.
4. Prueba de 10 segundos de `PHASE4_WHISPER_DEVICE_TEST.md` en el dispositivo (avanza, termina o falla con diagnóstico; nunca gira indefinidamente).
5. Ante fallo físico: conservar audio y capturar estado/código/progreso antes de tocar el diseño.

Advertencia de firma: la firma debug efímera actual puede impedir actualizar sobre la
instalación previa; puede requerir desinstalar. Fijar una firma estable queda **fuera
de alcance** de la Fase 4 y es el trabajo independiente siguiente.

---

## Próximas acciones inmediatas

1. Un agente arranca la **Tarea 1** (cadena del modelo) sobre la rama destino del plan.
2. En paralelo, otros toman **Tarea 2** y **Tarea 3** (ambas solo-JVM, sin red ni NDK).
3. Reservar el entorno con NDK para la **Tarea 4** una vez que 1 y 2 estén verdes.

## Bitácora de coordinación

- 2026-09-14 — Se crea este documento de seguimiento. Estado inicial: Fase 3 e
  interino completos; Fase 4 planificada, sin código. Tablero de 9 tareas y grafo de
  dependencias listos para reparto entre agentes.

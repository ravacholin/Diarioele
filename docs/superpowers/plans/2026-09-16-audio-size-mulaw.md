# Reducir el espacio del audio: WAV PCM16 → µ-law (G.711) 8-bit

## Contexto

Diarioele graba la clase con `AudioRecord` como **PCM lineal 16 kHz, mono, 16-bit**, y lo
guarda en segmentos WAV de 180 s en `filesDir/temporary_audio/`. Eso son **32.000 bytes/s
≈ 1,9 MB/min**; un día de 4 h acumula ~456 MB en disco hasta que el docente aprueba y se
borra el audio (`CleanupCoordinator`). El pico de disco durante la jornada es el problema.

El sample rate (16 kHz, exigido por Whisper) y los canales (mono) ya están al mínimo. El
único margen para achicar **sin un códec de compresión y sin cómputo extra** es bajar la
profundidad de 16 a 8 bits por muestra. Se usa **µ-law (G.711)**: re-cuantización por
muestra optimizada para voz (tabla de 256 entradas, sin frames ni codificación de entropía).

**Resultado:** ~**50% menos** de espacio (0,95 MB/min; el día de 4 h pasa de ~456 MB a
~228 MB), con cómputo prácticamente nulo (una búsqueda en tabla por muestra, incluso menos
E/S que hoy porque se escribe 1 byte en vez de 2). Decisión del usuario: acepta la pérdida
mínima de precisión de 8 bits (imperceptible en voz).

**Requisito duro (confirmado por el usuario):** Whisper local debe seguir transcribiendo.
Se cumple porque el lector propio decodifica µ-law → PCM16 float antes de pasar a Whisper;
el motor recibe el mismo `FloatArray` normalizado de siempre.

## Alcance y por qué es de bajo riesgo

El audio es **efímero**: nunca sobrevive al paso "APROBAR Y BORRAR AUDIO", no se persiste
en Room como blob (solo ruta + metadatos), y una sesión de grabación vive dentro de una
misma ejecución de la app. Por lo tanto **no hace falta migración de datos** ni
compatibilidad con archivos viejos. Solo hay dos consumidores del `.wav` guardado:

1. **Transcripción** — `PcmWindowReader` (código propio, control total).
2. **Reproducción de verificación** — `MediaPlayer.setDataSource(path)` en `CaptureScreen`.

## Enfoque

Guardar los segmentos como **WAV µ-law canónico** (formato PCM tag `7` = `WAVE_FORMAT_MULAW`,
8 bits, mono, 16 kHz, con chunk `fact`). Se mantiene la arquitectura actual de "escribir
cabecera de tamaño fijo al abrir, agregar cuerpo, parchear tamaños al cerrar".

### Archivos a modificar

**1. Nuevo: `app/src/main/java/com/capo/diarioclase/recording/audio/MuLawCodec.kt`**
- `encode(sample: Short): Byte` — G.711 µ-law estándar (bias 0x84, clamp a 32.635, signo +
  segmento + mantisa, complemento). Sin ramas costosas.
- `decodeTable: ShortArray(256)` — tabla precomputada µ-law byte → PCM16 (una sola vez).
- `decode(b: Byte): Short = decodeTable[b.toInt() and 0xFF]`.
- Reutilizado por el escritor (encode) y el lector (decode). Único lugar del algoritmo.

**2. `recording/audio/WavHeader.kt`**
- Añadir constantes del formato almacenado, sin romper las existentes:
  `WAVE_FORMAT_MULAW = 7`, `STORED_BITS_PER_SAMPLE = 8`, `STORED_BYTES_PER_SAMPLE = 1`, y
  la nueva `WAV_HEADER_BYTES` (cabecera µ-law con `fact` → tamaño fijo, ~58 bytes).
- `encode()` produce cabecera µ-law: `fmt` con `audioFormat=7`, `bitsPerSample=8`,
  `blockAlign=1`, `byteRate=SAMPLE_RATE` (16.000), más chunk `fact` con el nº de muestras.
- `forMuLaw(dataBytes)` reemplaza/complementa a `forPcm`. Al abrir se escribe con
  `dataBytes=0`; al cerrar se parchean RIFF size, `data` size y `fact` sampleCount.

**3. `recording/audio/FileSegmentStore.kt`**
- `open()`: escribir la nueva cabecera µ-law (líneas 18).
- `append()` (línea 23): en vez de escribir 2 bytes LE por `Short`, escribir **1 byte**
  `MuLawCodec.encode(pcm[i])`. Escribir el buffer completo de una vez (no byte a byte) para
  no aumentar E/S.
- `closeFile()` (línea 40): recalcular `data = length - WAV_HEADER_BYTES`, reescribir la
  cabecera (incluye parchear `fact`).
- Duración (línea 43): `bytes*1000 / (SAMPLE_RATE*CHANNELS*STORED_BYTES_PER_SAMPLE)`
  (denominador ahora 16.000 en vez de 32.000). SHA-256 y `.open/.ready` sin cambios.

**4. `processing/transcription/AudioWindowing.kt` (crítico para Whisper)**
- `PcmWindowReader.read()`: `bytesPerSample = STORED_BYTES_PER_SAMPLE` (1). Offset
  `WAV_HEADER_BYTES + firstSample*1`. Leer bloque de bytes µ-law y decodificar:
  `samples[i] = MuLawCodec.decode(rawBytes[i]) / 32_768f` (misma normalización que hoy).
- `validateCanonicalPcm16Wav` → renombrar/ajustar: aceptar `audioFormat == 7`,
  `bitsPerSample == 8`, mono, 16 kHz. **Localizar el chunk `data` recorriendo chunks** (no
  asumir offset 36 fijo) para tolerar el `fact`, y usar ese offset en `read()`. Los mensajes
  de error siguen en español.
- La planificación de ventanas (`AudioWindowPlanner`) no cambia: trabaja en ms.

**5. `ui/capture/CaptureScreen.kt` (reproducción)**
- `toggle()` (línea 43) sigue usando `MediaPlayer.setDataSource(segment.path)`. Android
  soporta WAV µ-law (`WAVE_FORMAT_MULAW`), así que **en principio no requiere cambios**.
- **Riesgo a verificar en dispositivo** (ver abajo). Fallback si el `MediaPlayer` del Moto
  g max no reprodujera µ-law WAV: decodificar µ-law → PCM16 en memoria y reproducir con
  `AudioTrack` (o escribir un WAV PCM16 temporal solo para escuchar). El código PCM ya
  existe, así que el fallback es acotado. `formatBytes` y los metadatos de la UI no cambian.

### Tests a actualizar (mismos archivos, nuevas expectativas)

- `recording/audio/WavHeaderTest.kt` — ahora describe µ-law: `audioFormat=7`, 8-bit, mono,
  16 kHz, byteRate=16.000, presencia y valor del chunk `fact`.
- `recording/audio/FileSegmentStoreTest.kt` — tamaño = 1 byte/muestra; duración recalculada.
- `recording/audio/PersistingSegmentStoreTest.kt` — metadatos con los nuevos tamaños.
- `processing/transcription/AudioWindowingTest.kt` — construir WAV µ-law de prueba; verificar
  que la ventana decodifica a los mismos floats esperados (round-trip encode→decode).
- `recording/service/RecordingCoordinatorTest.kt` — el corte por `MAX_SEGMENT_SAMPLES` es en
  muestras, no cambia; verificar que sigue segmentando a 180 s.
- **Nuevo** `recording/audio/MuLawCodecTest.kt` — round-trip encode/decode dentro de la
  tolerancia µ-law estándar; casos borde (0, ±máx, silencio).
- **Test de integración recomendado** para el requisito del usuario: PCM16 sintético →
  `FileSegmentStore` (µ-law) → `PcmWindowReader` → `FloatArray`, y comprobar que el error
  vs. el original está dentro de la tolerancia µ-law (garantiza que Whisper recibe audio
  válido). `WhisperTranscriptionEngineTest` no debería requerir cambios (consume `FloatArray`).

## Riesgos

- **Reproducción con `MediaPlayer`** en el dispositivo real: es el único punto que depende de
  soporte externo. Verificar en el Moto g max (encaja con el pendiente de prueba física de la
  Fase 6). Si falla, aplicar el fallback de decodificación in-app (arriba).
- **Cabecera de tamaño variable por `fact`**: al localizar el chunk `data` recorriendo chunks
  en el lector se elimina la fragilidad de offsets fijos; es más robusto que hoy.
- **Segmentos `.open.wav` a medio escribir** tras una actualización de la app: `repairOpenSegments`
  los cierra con la cabecera actual. Como una sesión vive en una sola ejecución, el riesgo es
  mínimo; si preocupa, marcar/limpiar `.open.wav` huérfanos al abrir.

## Verificación end-to-end

1. `./gradlew test` — unit + Robolectric verdes con las expectativas nuevas.
2. Test de integración PCM16 → µ-law → `FloatArray` dentro de tolerancia (Whisper OK).
3. `./gradlew :app:assembleDebug` compila.
4. **En el Moto g max** (protocolo Fase 6):
   - Grabar un bloque; confirmar en la UI que `formatBytes` muestra ~la mitad de MB para la
     misma duración: ~2,85 MB por segmento de 180 s (0,95 MB/min × 3 min) en vez de ~5,7 MB.
   - Botón **ESCUCHAR** reproduce el segmento (verificación del `MediaPlayer`).
   - **PROCESAR AUDIO**: la transcripción Whisper local produce la ficha correctamente
     (requisito explícito del usuario).
   - **APROBAR Y BORRAR AUDIO**: el audio se elimina como antes.
5. Confirmar en CI (PR contra la rama de trabajo) que sigue verde.

## Entrega

Rama `claude/audio-size-reduction-q9m6h8`; commits descriptivos; al terminar, push y PR
(rellenando la plantilla del repo si existe).

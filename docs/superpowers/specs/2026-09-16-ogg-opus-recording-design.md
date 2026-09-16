# Diseño: grabación temporal directa en OGG/Opus

Fecha: 2026-09-16  
Repositorio: `ravacholin/Diarioele`  
Base de trabajo: `feature/phase5-2-quality@358ab75`  
Rama: `feature/phase5-2-task-opus-recording`

## Objetivo

Reducir drásticamente el espacio temporal de las grabaciones de DiarioELE sin crear un WAV y comprimirlo después, sin enviar audio fuera del dispositivo y sin degradar de manera significativa la transcripción local con Whisper.

El formato nuevo será OGG con audio Opus mono. Whisper seguirá recibiendo PCM flotante mono a 16 kHz; la conversión desde Opus ocurrirá en memoria y solo para la ventana que se transcribe.

## Decisiones aprobadas

- Android mínimo 10, API 29.
- Captura mediante `AudioRecord`, igual que en la versión actual.
- Codificación inmediata mediante `MediaCodec` y contenedor OGG mediante `MediaMuxer`.
- Opus mono a 40 kbit/s como valor inicial.
- Segmentos de grabación de 60 segundos.
- Ventanas Whisper de 30 segundos con 2 segundos de solapamiento, sin cambios.
- Ningún WAV intermedio y ninguna etapa de compresión posterior.
- Los WAV ya existentes siguen siendo legibles y eliminables.
- El audio permanece siempre en almacenamiento interno privado y excluido de backup.
- Una falla de codificación, recuperación o transcripción nunca elimina el archivo fuente.

## Arquitectura

### Captura y persistencia

```text
AudioRecord PCM16 mono
        |
        v
AndroidOpusEncoder
MediaCodec audio/opus, 40 kbit/s
        |
        v
MediaMuxer OGG
        |
        v
<segmento>.open.ogg
        |
   cierre + validación
        |
        v
<segmento>.ready.ogg
```

`AudioRecord` conserva la captura PCM actual y entrega bloques pequeños. Esos bloques no se guardan como PCM: se envían inmediatamente al codificador Opus. El coordinador rota el segmento cada 960.000 muestras, equivalentes a 60 segundos a 16 kHz.

El almacenamiento de segmentos conserva la interfaz existente, pero la implementación nueva delega la escritura a un codificador incremental. El cierre solo produce un `ReadySegment` después de detener el muxer, sincronizar el descriptor, volver a abrir el OGG y comprobar que contiene una pista Opus con duración positiva.

### Lectura para Whisper

```text
ready.ogg
   |
MediaExtractor + MediaCodec
   |
PCM16 con frecuencia reportada por el decodificador
   |
remuestreo explícito cuando no sea 16 kHz
   |
FloatArray mono 16 kHz
   |
whisper.cpp
```

`TranscriptionCoordinator` dependerá de `AudioWindowReader` en vez de llamar directamente a `PcmWindowReader`. Un lector adaptativo seleccionará:

- `WavWindowReader` para `.wav` heredados;
- `OpusWindowReader` para `.ogg` nuevos.

La decodificación debe avanzar secuencialmente hasta cubrir la ventana solicitada y descartar PCM anterior. No se escribirá PCM decodificado en disco. La salida exacta de cada lectura será `(endMs - startMs) * 16` muestras, salvo el final legítimamente más corto del archivo.

### Recuperación

El WAV actual se repara reescribiendo su encabezado. OGG requiere otra política:

1. Rotar y cerrar cada 60 segundos para limitar la exposición.
2. Mantener `.open.ogg` mientras el muxer está activo.
3. En el arranque, abrir cada `.open.ogg` con `MediaExtractor`.
4. Si existe una pista Opus y pueden leerse muestras con timestamps crecientes, conservar la parte legible y promoverla a `.ready.ogg` con la duración observada.
5. Si el archivo no puede abrirse, mantenerlo intacto como `.open.ogg`, registrar la recuperación pendiente y no borrar ni sobrescribir sus bytes.

La recuperación nunca inventa duración a partir del tamaño comprimido. La duración proviene de los timestamps de los paquetes legibles.

## Contratos principales

```kotlin
enum class AudioContainer { WAV_PCM16, OGG_OPUS }

fun AudioContainer.Companion.fromPath(path: String): AudioContainer

fun interface AudioWindowReader {
    fun read(file: File, plan: AudioWindowPlan): FloatArray
}

interface StreamingAudioEncoder : AutoCloseable {
    fun append(pcm: ShortArray, offset: Int, count: Int)
    fun finish(): EncodedAudioInfo
    fun abort()
}

data class EncodedAudioInfo(
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val mimeType: String,
)

interface AudioCapabilityProbe {
    fun opusOggSupport(): AudioCapability
}
```

La selección del formato no se basa solamente en la versión de Android. Antes de iniciar una clase, el probe debe comprobar que el teléfono ofrece un encoder `audio/opus`, un decoder compatible y `MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG`.

## Calidad de audio

El valor inicial será 40 kbit/s porque prioriza voces lejanas, números, nombres y superposición parcial. La aceptación física comparará WAV PCM con Opus a 32, 40 y 48 kbit/s sobre el mismo corpus de aula. El bitrate solo bajará de 40 si la transcripción conserva los mismos datos pedagógicos.

La comparación mínima incluye:

- palabras totales y sustituciones relevantes;
- páginas y ejercicios;
- números y letras como `4b`;
- tarea;
- nombres propios;
- voz lejana y ruido de aula.

## Fallas y comportamiento

- Encoder Opus ausente: bloquear el inicio y mostrar un error accionable; no iniciar una grabación sin destino seguro.
- Error durante `append`: cerrar de emergencia, conservar `.open.ogg` y marcar el bloque como interrumpido.
- Error al finalizar muxer: conservar `.open.ogg`; no crear `.ready.ogg` falso.
- OGG inválido durante transcripción: devolver `AUDIO_SOURCE_UNSUPPORTED` o `INVALID_AUDIO` sin borrar el archivo.
- Frecuencia PCM inesperada: remuestrear explícitamente a 16 kHz.
- Canal inesperado: mezclar a mono antes de Whisper.
- Poco espacio, batería crítica o temperatura excesiva: mantener las pausas preventivas ya definidas por DiarioELE.

## Compatibilidad y migración

No se requiere migración Room para distinguir formatos: el contenedor se deriva de la extensión controlada por la aplicación. Las filas existentes apuntan a `.ready.wav`; las nuevas apuntarán a `.ready.ogg`.

La limpieza buscará ambos formatos por `SegmentId`. La reproducción desde la UI continuará usando el reproductor Android, que acepta ambos. Los nombres y estados Room no cambian.

## Pruebas

### Unitarias JVM

- detección de contenedor por ruta;
- selección del lector WAV u OGG;
- rotación exacta a 60 segundos;
- contrato del encoder con un fake incremental;
- promoción `.open.ogg` a `.ready.ogg` solo después de validación;
- conservación de archivos inválidos;
- remuestreo 48 kHz a 16 kHz;
- mezcla estéreo a mono;
- limpieza compatible con WAV y OGG.

### Instrumentadas Android

- disponibilidad real de encoder y decoder Opus;
- archivo OGG producido por `MediaCodec`/`MediaMuxer` y leído por `MediaExtractor`;
- duración y timestamps monótonos;
- decodificación de ventanas 0-30, 28-58 y final;
- cierre forzado y recuperación del último segmento.

### Moto g max

- clases sintéticas de 90, 60 y 50 minutos con pantalla bloqueada;
- pausa y reanudación;
- kill/restart durante un segmento;
- batería y temperatura antes/después;
- espacio total ocupado;
- comparación de transcripción WAV/Opus;
- aprobación final y eliminación verificada de todos los temporales.

## Criterios de aceptación

- Una grabación nueva no crea archivos WAV.
- Noventa minutos ocupan como máximo 35 MB a 40 kbit/s, admitiendo overhead del contenedor.
- Whisper recibe siempre PCM flotante mono a 16 kHz.
- Las ventanas y checkpoints existentes mantienen sus tiempos y solapamiento.
- Un cierre inesperado no afecta segmentos ya marcados `READY`.
- Un archivo no recuperable se conserva y nunca se borra automáticamente.
- Los WAV heredados siguen transcribiéndose, reproduciéndose y eliminándose.
- La comparación física no muestra pérdida pedagógicamente significativa frente al WAV.
- La prueba completa del Moto g max no muestra calentamiento, consumo o lentitud que impidan una clase de cuatro horas.

## Exclusiones

- FFmpeg.
- Compresión de WAV después de grabar.
- Envío de audio a servicios remotos.
- Cambio del modelo Whisper.
- Diarización.
- Procesamiento remoto durante la grabación.
- Eliminación automática de fuentes dañadas.

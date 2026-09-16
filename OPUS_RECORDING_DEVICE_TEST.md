# Validación OGG/Opus en Moto g max

Versión objetivo: `0.5.3-opus` (`versionCode 10`)

Esta prueba física es obligatoria antes de integrar la migración a `main`. GitHub Actions verifica pruebas JVM, lint y compilación de los APK de aplicación e instrumentación, pero no ejecuta `MediaCodec` ni una grabación prolongada en el Moto g max.

## APK exacto

- Rama: `feature/phase5-2-task-opus-recording`
- Commit: completar con el SHA verde final
- Workflow: completar con el enlace de GitHub Actions
- SHA-256 de `app-debug.apk`: completar después de descargar el artefacto
- Dispositivo: Moto g max con Android 16

## Preparación

1. Instalar el APK `DiarioClase-Android-debug` de la ejecución verde final.
2. Anotar batería, temperatura del dispositivo y espacio libre.
3. Mantener el teléfono desconectado de la alimentación y cerrar otras aplicaciones pesadas.
4. Preparar una fuente de voz en español con números, nombres propios, páginas, ejercicios y tarea.

## Grabación prolongada

Grabar tres bloques de 90, 60 y 50 minutos. Bloquear la pantalla durante cada bloque y usar pausa/reanudación al menos una vez.

Registrar para cada bloque:

| Medición | 90 min | 60 min | 50 min |
|---|---:|---:|---:|
| Batería inicial/final |  |  |  |
| Temperatura inicial/final |  |  |  |
| Cantidad de segmentos OGG |  |  |  |
| Bytes OGG totales |  |  |  |
| Cortes, lentitud o audio perdido |  |  |  |

Criterios:

- No se crea ningún WAV nuevo.
- Cada segmento completo dura aproximadamente 60 segundos.
- Noventa minutos ocupan como máximo 35 MB.
- La grabación continúa con la pantalla bloqueada y el equipo no presenta calentamiento, consumo o lentitud incompatibles con una jornada de cuatro horas.

## Recuperación

1. Iniciar otro bloque y forzar el cierre de la aplicación a mitad de un segmento.
2. Volver a abrir DiarioELE.
3. Confirmar que los segmentos ya cerrados permanecen intactos.
4. Confirmar que el último `.open.ogg` válido se recupera como `.ready.ogg`.
5. Si el último archivo no es recuperable, confirmar que se conserva y no se borra automáticamente.

Resultado y observaciones:

- Estado recuperado:
- Segmentos antes/después:
- Archivos preservados:

## Transcripción local y calidad

Finalizar la jornada y transcribirla localmente. Registrar duración de transcripción y verificar:

- temas;
- actividades;
- páginas y ejercicios;
- tarea;
- números y nombres propios;
- ausencia de envío remoto del audio.

Comparar además el mismo fragmento de clase guardado en WAV PCM16 y en OGG/Opus a 32, 40 y 48 kbit/s. Anotar diferencias de palabras y de cada campo pedagógico. La aceptación requiere que no exista una pérdida pedagógicamente significativa frente al WAV; mantener 40 kbit/s salvo que 48 kbit/s corrija un error observado y documentado.

| Comparación | WAV | Opus 32 | Opus 40 | Opus 48 | Diferencia relevante |
|---|---|---|---|---|---|
| Duración del audio |  |  |  |  |  |
| Tamaño |  |  |  |  |  |
| Duración de transcripción |  |  |  |  |  |
| Palabras/números/nombres |  |  |  |  |  |
| Páginas y ejercicios |  |  |  |  |  |
| Tarea |  |  |  |  |  |

## Compatibilidad y limpieza

1. Confirmar que un WAV heredado todavía se transcribe y reproduce.
2. Aprobar el diario.
3. Confirmar que se eliminan los temporales `.ready.wav` y `.ready.ogg` correspondientes.
4. Confirmar que no quedan filas temporales ni archivos de otras sesiones eliminados por error.

## Resultado final

- Fecha:
- Resultado: `PENDIENTE / APROBADO / RECHAZADO`
- Bitrate aceptado: `40 kbit/s` salvo evidencia registrada para cambiarlo
- Problemas observados:
- Firma de validación:

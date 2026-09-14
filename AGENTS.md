# Instrucciones de continuidad para agentes

## Lectura obligatoria antes de modificar código

1. Leer `PHASE4_HANDOFF.md`.
2. Leer `PHASE4_RECOVERY_STATUS.md`.
3. Consultar la especificación en `PHASE4_WHISPER_LOCAL_DESIGN.md`.
4. Usar `PHASE4_WHISPER_LOCAL_IMPLEMENTATION_PLAN.md` como plan detallado, teniendo en cuenta que la rama real de trabajo es `feature/phase4-whisper-recovery`.

## Fuente de verdad

- Repositorio: `ravacholin/Diarioele`
- Rama activa: `feature/phase4-whisper-recovery`
- PR borrador: `#1`
- Head documental al crear este archivo: `e079a9b47b09e4bd1e5de92e0378ddfa67ea2090`
- Último código funcional validado: `a53b32145cb044b8539f05231031658c0ae2d6c6` (Tasks 7, 8 y 9)
- Última validación completa del código: GitHub Actions `#74`, run `34889834345`

Antes de trabajar, comprobar que estos datos no hayan sido superados por un checkpoint más reciente documentado en `PHASE4_HANDOFF.md`.

## Forma de trabajo obligatoria

- No depender de un entorno temporal como única copia.
- Trabajar en una rama y subir cada bloque coherente a GitHub.
- No marcar una tarea como terminada hasta que GitHub Actions quede verde.
- Después de cada bloque validado, actualizar `PHASE4_HANDOFF.md` y `PHASE4_RECOVERY_STATUS.md`.
- Registrar SHA, número de workflow, qué cambió, qué falta y cualquier error pendiente.
- No borrar audio, transcripciones, evidencia ni checkpoints antes de que el usuario apruebe explícitamente la ficha.
- No agregar permiso de Internet ni servicios remotos de transcripción.
- Mantener español fijo y el diseño minimalista, brutalista, moderno, elegante, oscuro y monocromático.
- No reintroducir la descarga de modelos de idioma de Android.
- Conservar los modos `CONSERVATIVE`, `BALANCED` y `EXHAUSTIVE` como configuración de interpretación.
- Si una prueba física falla, conservar el audio y registrar estado, progreso y error antes de cambiar el diseño.

## Próxima tarea

Tasks 7, 8 y 9 están completas y validadas por GitHub Actions `#74` (`a53b321`):

- Task 7: UI observable conectada al planificador persistente, sin descarga de idioma.
- Task 8: la limpieza temporal también borra y cuenta `transcription_runs` y `transcription_checkpoints`.
- Task 9: motor de reconocimiento anterior eliminado, versión `0.4.0-whisper`, APK offline listo.

La próxima tarea es la **prueba física en el Moto g max** siguiendo `PHASE4_WHISPER_DEVICE_TEST.md`. Ninguna CI verde reemplaza esa prueba, y el audio no se borra sin aprobación explícita del usuario.

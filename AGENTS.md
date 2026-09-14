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
- Último código funcional validado: `5b60dd0f5d21cda21b173728ad3228467fbf4e14` (Task 7)
- Última validación completa del código: GitHub Actions `#67`, run `34887686385`

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

Task 7 (UI observable sin descarga de idioma) está validada por GitHub Actions `#67`.

La próxima tarea es Task 8, limpieza segura de `transcription_runs` y `transcription_checkpoints`. La lista exacta, criterios y riesgos están en `PHASE4_HANDOFF.md`.

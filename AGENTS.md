# Instrucciones de continuidad para agentes

## Lectura obligatoria antes de modificar código

Para trabajo de Fase 5, leer en este orden:

1. `PHASE5_HANDOFF.md`.
2. `docs/superpowers/specs/2026-09-14-contextual-interpretation-design.md`.
3. `docs/superpowers/plans/2026-09-14-contextual-interpretation.md`.
4. `PHASE5_AGENT_COLLABORATION.md`.
5. `PHASE4_HANDOFF.md` y `PHASE4_RECOVERY_STATUS.md` solo para comprender la base estable que no debe romperse.

El plan define la implementación técnica. `PHASE5_AGENT_COLLABORATION.md` prevalece para ramas, dependencias, propiedad de archivos, revisiones e integración.

## Fuente de verdad

- Repositorio: `ravacholin/Diarioele`
- Base estable: `feature/phase4-whisper-recovery`
- Rama de integración activa: `feature/phase5-contextual-interpretation`
- Estado actualizado: `PHASE5_HANDOFF.md`
- Fase 4: completa, validada por CI y probada en Moto g max.
- Fase 5: diseñada; código de producción todavía no implementado.

Antes de trabajar, comprobar que estos datos no hayan sido superados por un checkpoint más reciente en `PHASE5_HANDOFF.md`.

## Forma de trabajo obligatoria

- Crear una rama por tarea desde el último head verde de la rama de integración.
- Abrir cada PR de tarea contra `feature/phase5-contextual-interpretation`, no contra `main`.
- No permitir escrituras concurrentes sobre una misma rama.
- No iniciar una tarea bloqueada según el DAG de colaboración.
- No modificar archivos reservados por otra tarea sin detenerse y declararlo.
- Escribir pruebas antes de producción y usar clientes/transportes falsos.
- No marcar una tarea como terminada hasta que sus pruebas y GitHub Actions estén verdes.
- Después de integrar cada bloque, actualizar `PHASE5_HANDOFF.md` con PR, SHA, workflow, pruebas, riesgos y siguiente tarea.
- No depender de un entorno temporal como única copia.
- No guardar ni publicar claves, cuerpos con datos privados o credenciales de prueba reales.

## Restricciones del producto

- Mantener Whisper local y no modificar ventanas, deduplicación, checkpoints ni conservación de Fase 4.
- El audio nunca se envía a un proveedor.
- Proveedores permitidos: Gemini, Groq y OpenRouter `openrouter/free`.
- Orden predeterminado: Gemini, Groq, OpenRouter y fallback local.
- No incorporar Cloudflare, Mistral, servidor propio ni segundo modelo Android.
- No aceptar ids libres de modelos ni cambiar automáticamente a modelos pagos.
- Exigir consentimiento y credencial independientes por proveedor.
- Mantener el router secuencial; una respuesta válida detiene la cadena.
- No usar red ni claves reales en CI.
- Conservar los modos `CONSERVATIVE`, `BALANCED` y `EXHAUSTIVE` como proyección local.
- No sobrescribir campos editados por el usuario.
- No borrar audio, transcripciones, evidencia, caché válida ni checkpoints ante fallos.
- Mantener español fijo y el diseño minimalista, brutalista, moderno, elegante, oscuro y monocromático.

## Próxima tarea

La **Fase 5 está completa a nivel de código**: las Tasks 1–9 (parte de código) quedaron integradas y verdes en `feature/phase5-contextual-interpretation` (`5ac8132`), con la versión `0.5.0-free-router`. Lo **único pendiente es la prueba física en el Moto g max** (protocolo en `PHASE5_FREE_INFERENCE_DEVICE_TEST.md`), que requiere el dispositivo del usuario y ninguna CI reemplaza.

Pendientes menores declarados para una iteración futura (no bloquean la prueba): el hook de fallos simulados en debug (para encadenar 429 entre proveedores sin quitar la red) y el panel de estado de ejecución (procedencia `MIXTO/LOCAL`, `REINTENTAR`/`CONTINUAR LOCAL`). El estado y las instrucciones están en `PHASE5_HANDOFF.md`.

# Instrucciones de continuidad para agentes

## Lectura obligatoria antes de modificar código

Para trabajo de Fase 5, leer en este orden:

1. `PHASE5_HANDOFF.md`.
2. `docs/superpowers/specs/2026-09-15-phase5-2-quality-design.md`.
3. `docs/superpowers/plans/2026-09-15-phase5-2-integrity.md`.
4. `docs/superpowers/plans/2026-09-15-phase6-quality-loop.md`.
5. `PHASE5_2_AGENT_COLLABORATION.md`.
6. Los documentos de Fase 4 y Fase 5 inicial solo como antecedentes.

Los planes definen la implementación técnica. `PHASE5_2_AGENT_COLLABORATION.md` prevalece para ramas, dependencias, propiedad de archivos, revisiones e integración.

## Fuente de verdad

- Repositorio: `ravacholin/Diarioele`
- Base estable: `main` en `63c4be6cfe5c07f42dd91d8f6948b194e276da56`
- Rama de integración activa: `feature/phase5-2-quality`
- PR de diseño y planes: `#15`
- Estado actualizado: `PHASE5_HANDOFF.md`
- Fase 4: completa, validada por CI y probada en Moto g max.
- Fase 5.1: integrada en `main` como `0.5.1-free-router`; falta validación física.
- Próxima entrega: `0.5.2-integrity`.

Antes de trabajar, comprobar que estos datos no hayan sido superados por un checkpoint más reciente en `PHASE5_HANDOFF.md`.

## Forma de trabajo obligatoria

- Crear una rama por tarea desde el último head verde de la rama de integración.
- Abrir cada PR de tarea contra `feature/phase5-2-quality`, no contra `main`.
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

Ejecutar `0.5.2-integrity` según `docs/superpowers/plans/2026-09-15-phase5-2-integrity.md`.

La secuencia comienza con I0 (readiness) e I1 (contratos). Solo después de integrar ambos y tener CI verde se habilitan I2–I6. La propiedad de archivos, ramas y dependencias está en `PHASE5_2_AGENT_COLLABORATION.md`.

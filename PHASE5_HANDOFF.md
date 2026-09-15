# Fase 5: estado y continuidad

Última actualización: 2026-09-15

## Estado actual

La Fase 5 está en implementación colaborativa. **Task 1 (contrato común y escenarios sintéticos) quedó integrada y verde**; con eso se habilita la ola 1 (Tasks 2, 3 y 4 en paralelo).

La Fase 4 permanece completa, validada por CI y probada con éxito en el Moto g max. La rama de Fase 5 parte de esa base funcional y no debe alterar el motor Whisper local.

## Fuente de verdad

- Repositorio: `ravacholin/Diarioele`
- Base estable: `feature/phase4-whisper-recovery`
- Rama de integración: `feature/phase5-contextual-interpretation`
- Head documental inicial de Fase 5: `d4bf31be623e7d6bacb8bb42fb71ebf065f45d37`
- Diseño: `docs/superpowers/specs/2026-09-14-contextual-interpretation-design.md`
- Plan: `docs/superpowers/plans/2026-09-14-contextual-interpretation.md`
- Coordinación multiagente: `PHASE5_AGENT_COLLABORATION.md`
- PR central borrador: `#3`
- Baseline integrado: `cc45637818c8693056590a186d4aa2dca0b7dc20`
- GitHub Actions baseline: run `#96` (`34900046641`), SUCCESS

Antes de modificar código, comprobar en GitHub si existe un checkpoint más reciente en este documento.

## Decisión funcional

Cadena fija de interpretación:

```text
Gemini Flash -> Groq -> OpenRouter Free -> extractor local
```

El sistema:

- transcribe audio localmente con Whisper;
- envía solamente paquetes de texto y referencias artificiales;
- usa un proveedor por vez;
- detiene la cadena en la primera respuesta validada;
- continúa ante cuota, error, timeout o respuesta inválida;
- conserva una salida local editable si todas las APIs fallan;
- no exige corpus inicial;
- no habilita modelos pagos;
- no incluye Cloudflare, Mistral ni otro modelo Android en esta fase.

La garantía de costo requiere claves de cuentas o proyectos sin facturación. La aplicación bloquea ids fuera del catálogo gratuito, pero no controla decisiones de facturación tomadas fuera de DiarioELE.

## Estado por tarea

| Tarea | Estado | Dependencias | Evidencia |
|---|---|---|---|
| 0. Readiness colaborativo | COMPLETA | ninguna | PR #4, run #96 verde, `cc456378` |
| 1. Contratos y escenarios | COMPLETA | Task 0 completa | PR #5, run #101 verde, `f22354b` → merge `4e7c302` |
| 2. Catálogo y credenciales | HABILITADA | Task 1 | sin PR |
| 3. Paquetes contextuales | HABILITADA | Task 1 | sin PR |
| 4. Prompt y clientes HTTP | HABILITADA | Task 1 | sin PR |
| 5. Validación y reducción | BLOQUEADA | Tasks 3 y 4 | sin PR |
| 6. Caché Room | BLOQUEADA | Task 1 | sin PR |
| 7. Router y fallback | BLOQUEADA | Tasks 2, 4, 5 y 6 | sin PR |
| 8. Integración y UI | BLOQUEADA | Task 7 | sin PR |
| 9. Feedback y release | BLOQUEADA | Task 8 | sin PR |

## Siguiente acción exacta

Task 1 está integrada y el contrato quedó congelado. Se habilita la **ola 1**: Tasks 2, 3 y 4, cada una en su rama y con PR contra `feature/phase5-contextual-interpretation`.

```text
Task 2 -> feature/phase5-task-02-credentials
Task 3 -> feature/phase5-task-03-packets
Task 4 -> feature/phase5-task-04-provider-clients
```

Reglas de la ola 1:

- Cada rama parte del último head verde de la integración (`4e7c302`).
- Task 4 puede importar el contrato de Task 1 pero no inventar variantes; coordina con Task 2 solo por `ProviderProfile` y el store de credenciales.
- Ninguna redefine enums, modelos ni políticas ya congelados en `processing/semantic/InferenceModels.kt`.
- Task 5 espera Tasks 3 y 4; Task 6 puede arrancar tras Task 1 pero se integra antes de Task 7.
- No marcar una tarea como completa sin CI verde de su PR.

Contrato congelado disponible en `app/src/main/java/com/capo/diarioclase/processing/semantic/` (`InferenceModels.kt`, `InferenceProviderClient.kt`) y fixtures en `app/src/test/.../semantic/`.

## Restricciones de implementación

- Gemini, Groq y OpenRouter son los únicos proveedores remotos.
- OpenRouter usa exclusivamente `openrouter/free`.
- Los modelos y endpoints son constantes de un catálogo, no campos libres.
- El consentimiento y la clave son independientes por proveedor.
- Las claves se almacenan con Android Keystore y jamás en Room.
- CI usa transportes falsos, nunca red o claves reales.
- El audio no sale del dispositivo.
- El router es estrictamente secuencial.
- Una respuesta no avanza si su evidencia no referencia spans locales válidos.
- Cambiar de modo solo reproyecta claims locales.
- Los campos editados no se sobrescriben.
- Los fallos no eliminan datos ni resultados válidos anteriores.
- La prueba física usa fallos simulados disponibles únicamente en debug.

## Escenarios sintéticos mínimos

Task 1 debe incluir ejemplos pequeños que cubran al menos:

- “Página cuarenta y siete, ejercicio tres. No, el cuatro.”
- “El tres lo hicimos; el cuatro queda para casa.”
- listas y rangos de páginas o ejercicios;
- tarea expresada sin la palabra “tarea”;
- pregunta del alumnado que no debe convertirse en actividad;
- ejemplo o cita que no debe convertirse en tarea;
- plan futuro frente a actividad realizada;
- corrección que afecta solo a uno de varios ejercicios;
- referente ambiguo marcado `UNCERTAIN`;
- las cinco categorías permanentes;
- evidencia distribuida entre varios spans.

No se necesita audio real ni corpus. Cada error observado luego en el teléfono se convierte en el escenario sintético mínimo que lo reproduzca.

## Protocolo de checkpoint

Último checkpoint integrado:

```text
Task integrada: 1 — Contrato común y escenarios sintéticos
PR: #5 (base feature/phase5-contextual-interpretation)
Base SHA: 4af41616b4bbfe5a824cce304d61eae5ff770346
Head SHA: f22354b02925ac7bcc4d242df62a152c9eca19bb
Merge SHA: 4e7c30238fdfe0393e6b5432bfea168c9a9ea418
Workflow: run #101 (pull_request) SUCCESS y run #100 (push) SUCCESS
Pruebas: testDebugUnitTest (incl. InferenceContractTest) + lintDebug + assembleDebug + assembleDebugAndroidTest
Resultado: verde; contrato congelado (modelos, interfaz, orden de spans, estado→campo, códec JSON, fixtures)
Riesgos pendientes: ninguno de producción; la validación semántica adversarial es de Task 5
Próxima tarea habilitada: ola 1 (Tasks 2, 3 y 4)
```

Después de cada integración, reemplazar el bloque anterior con el mismo formato. No marcar una tarea como completa basándose solamente en el reporte de un agente.

## Correcciones incorporadas tras revisión independiente

- CI habilitada para la rama de integración, ramas de tareas y PRs hacia Fase 5.
- Promesa de costo reformulada como ausencia de escalado automático a rutas pagas.
- Inferencia remota desactivada por defecto y modo local permanente.
- Evidencia múltiple, `claimKey`, supersesiones y política estado→campo pasan a Task 1.
- Orden de spans incluye ordinal de segmento.
- Dos requests máximos, circuit breaker, deadline y checkpoint reanudable.
- Transporte con allowlist, límites y sin redirects con credenciales.
- Caché acotado por sesión y versionado por validador.
- Protección de edición por campo.
- Feedback/migración adicional diferidos para evitar complejidad sin uso.
- Cierre sobre release no depurable.

## Historial documental

- `d4bf31be623e7d6bacb8bb42fb71ebf065f45d37`: diseño y plan del router gratuito.
- `4fbc960666826f98ada81e9de60fdf045736c865`: protocolo de colaboración multiagente.
- `c4085ce10fbdc66d6bd2fc0b84d680ebfc23b695`: triggers de CI de Fase 5 corregidos.
- `2143eca43c25d94aff89615e9ed5495b7919363b`: bloqueos técnicos de revisión resueltos en el plan.
- `7a9393514e2ca572df5345235ebaf6b04406dde2`: readiness y propiedad de archivos actualizados.
- `cc45637818c8693056590a186d4aa2dca0b7dc20`: PR #4 integrado después de GitHub Actions run #96 verde.
- `4e7c30238fdfe0393e6b5432bfea168c9a9ea418`: PR #5 (Task 1) integrado después de GitHub Actions run #101 verde.

## Condición de cierre

La Fase 5 no se declara completa hasta tener CI verde, APK exacto, escaneo de secretos, migraciones verificadas y prueba física satisfactoria de Gemini, fallback remoto y fallback local en el Moto g max.

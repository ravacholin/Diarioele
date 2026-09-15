# Fase 5: estado y continuidad

Última actualización: 2026-09-15

## Estado actual

La Fase 5 está en implementación colaborativa. **La ola 1 (Tasks 1, 2, 3 y 4) está integrada y verde** en `feature/phase5-contextual-interpretation` (`7178106`); con eso se habilita la ola 2 (Task 5: validación y reducción; Task 6: caché Room).

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
| 2. Catálogo y credenciales | COMPLETA | Task 1 | PR #7, run #107 verde, `2ed49c4` → merge `53ee947` |
| 3. Paquetes contextuales | COMPLETA | Task 1 | PR #6, run #105 verde, `f68b841` → merge `9054aaf` |
| 4. Prompt y clientes HTTP | COMPLETA | Task 1 | PR #8, run #113 verde, `5b73b29` → merge `7178106` |
| 5. Validación y reducción | HABILITADA | Tasks 3 y 4 | sin PR |
| 6. Caché Room | HABILITADA | Task 1 | sin PR |
| 7. Router y fallback | BLOQUEADA | Tasks 2, 4, 5 y 6 | sin PR |
| 8. Integración y UI | BLOQUEADA | Task 7 | sin PR |
| 9. Feedback y release | BLOQUEADA | Task 8 | sin PR |

## Siguiente acción exacta

La **ola 1** (Tasks 2, 3 y 4) está integrada y verde. Se habilita la **ola 2**: Tasks 5 y 6, cada una en su rama y con PR contra `feature/phase5-contextual-interpretation`.

```text
Task 5 -> feature/phase5-task-05-validation
Task 6 -> feature/phase5-task-06-cache
```

Reglas de la ola 2:

- Cada rama parte del último head verde de la integración (`7178106`).
- Task 5 (validador, reductor, procedencia, projector) amplía `ClaimOrigin` con `GEMINI/GROQ/OPENROUTER` y construye `EvidenceRef` desde spans locales; nunca acepta evidencia textual del modelo.
- Task 6 (caché Room) agrega la entidad `interpretation_cache`, la migración 4→5 y suma la caché a la limpieza temporal; se integra antes de Task 7.
- Si ambas tocan `FullJourneyTest.kt`, esa prueba queda reservada para Task 6 o el integrador.
- Ninguna redefine enums, modelos ni políticas congelados en `processing/semantic/InferenceModels.kt`, ni depende de una tarea hermana no integrada (lección de Task 4: dependía de `FreeProviderCatalog` de Task 2 y rompió su rama).
- No marcar una tarea como completa sin CI verde de su PR.

Artefactos disponibles tras la ola 1: contrato (`InferenceModels.kt`, `InferenceProviderClient.kt`), catálogo/credenciales (`FreeProviderCatalog`, `ProviderSettingsStore`, `ProviderCredentialStore`), paquetes (`InterpretationPacketBuilder`) y adaptadores (`InterpretationPromptFactory`, `InferenceHttpTransport`, `GeminiProviderClient`, `OpenAiCompatibleProviderClient`).

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
Ola integrada: 1 (Tasks 2, 3 y 4) sobre Task 1
Task 1: PR #5, head f22354b, merge 4e7c302, run #101 SUCCESS
Task 3: PR #6, head f68b841, merge 9054aaf, run #105 SUCCESS
Task 2: PR #7, head 2ed49c4, merge 53ee947, run #107 SUCCESS
         (run de push #106 rojo por 429 de Maven en dependencias, no del código)
Task 4: PR #8, head 5b73b29, merge 7178106, run #113 SUCCESS
         (primer intento 97f2b35 rojo: dependía de FreeProviderCatalog de Task 2;
          se corrigió para que la rama sea autónoma según el DAG)
Base SHA de la ola 2: 7178106ec0f3c3a95fe37e8cccb698a2f85579d6
Pruebas: testDebugUnitTest + lintDebug + assembleDebug + assembleDebugAndroidTest
Resultado: verde; catálogo, credenciales cifradas, paquetes y adaptadores integrados
Riesgos pendientes: la validación semántica adversarial y la caché son de la ola 2
Próxima ola habilitada: 2 (Task 5 con Tasks 3 y 4; Task 6 tras Task 1)
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
- `9054aaf680968cac1f6818a97fda57db2ac3b44e`: PR #6 (Task 3) integrado después de run #105 verde.
- `53ee9475a5ec719e90b48b71b6e9b87741598d92`: PR #7 (Task 2) integrado después de run #107 verde.
- `7178106ec0f3c3a95fe37e8cccb698a2f85579d6`: PR #8 (Task 4) integrado después de run #113 verde. Cierra la ola 1.

## Condición de cierre

La Fase 5 no se declara completa hasta tener CI verde, APK exacto, escaneo de secretos, migraciones verificadas y prueba física satisfactoria de Gemini, fallback remoto y fallback local en el Moto g max.

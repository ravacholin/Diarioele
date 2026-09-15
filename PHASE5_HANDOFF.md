# Fase 5: estado y continuidad

Última actualización: 2026-09-15

## Estado actual

La Fase 5 está en implementación colaborativa. **Las olas 1 (Tasks 1–4), 2 (Tasks 5 y 6) y 3 (Task 7) están integradas y verdes** en `feature/phase5-contextual-interpretation` (`9886202`); con eso se habilita la ola 4 (Task 8: integración con el procesamiento y UI; luego Task 9: release y prueba física).

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
| 5. Validación y reducción | COMPLETA | Tasks 3 y 4 | PR #9, run #117 verde, `6f3b674` → merge `b08e850` |
| 6. Caché Room | COMPLETA | Task 1 | PR #10, run #119 verde, `0b144da` → merge `a9812c0` |
| 7. Router y fallback | COMPLETA | Tasks 2, 4, 5 y 6 | PR #11, run #124 verde, `bde19f9` → merge `9886202` |
| 8. Integración y UI | HABILITADA | Task 7 | sin PR |
| 9. Feedback y release | BLOQUEADA | Task 8 | sin PR |

## Siguiente acción exacta

Las **olas 1–3** (Tasks 1–7) están integradas y verdes. Se habilita la **ola 4**: Task 8 (integración con el procesamiento y configuración visible), en la rama:

```text
Task 8 -> feature/phase5-task-08-integration-ui
```

Reglas de la ola 4 (Task 8):

- La rama parte del último head verde de la integración (`9886202`).
- Componer en `DiarioClaseApp` catálogo, stores, transporte, los dos tipos de cliente, validador, caché, reductor, router y fallback. `DiarioClaseApp` ya registra `MIGRATION_4_5` (agregado en Task 6).
- Separar el estado de interpretación del éxito de Whisper; el router arranca solo después de completar Whisper; persistir paquete/proveedor/intento/resultado y procesar como máximo una unidad reanudable por paso. **No** modificar el motor Whisper, ventanas, deduplicación ni checkpoints.
- Reemplazar la extracción directa por paquetes → routing → reducción → proyección. Cambiar de modo no llama a la red. Los campos editados sobreviven. La reapertura usa caché. La cancelación conserva datos.
- UI compacta por proveedor: activar, explicación de envío de texto, consentimiento, campo de clave oculto, guardar, probar, borrar, modelo fijo gratuito y últimos cuatro caracteres; sin escribir el id de modelo. Probar conexión con un prompt fijo mínimo (OpenRouter: `GET /api/v1/key` y advertir si no es free-tier o hay gasto sin límite). Mostrar proveedor actual, paquete actual, fallbacks y procedencia `GEMINI/GROQ/OPENROUTER/MIXTO/LOCAL`, con `REINTENTAR INFERENCIA` y `CONTINUAR CON FICHA LOCAL`.
- Archivos reservados de Task 8: `TranscriptionCoordinator.kt`, `TranscriptionWorker.kt`, `DiarioClaseApp.kt`, `AndroidCaptureActions.kt`, `CaptureUiState.kt`, `CaptureViewModel.kt`, `CaptureScreen.kt` y sus pruebas.
- Nunca red ni claves reales en CI; los fallos simulados solo en debug.

Task 9 (release + prueba física en el Moto g max) queda para el final y **requiere el teléfono del usuario**.

Artefactos disponibles tras las olas 1–3: contrato, catálogo/credenciales, paquetes, adaptadores, validador, reductor, projector por estado, caché y router (`FreeInferenceRouter`, `ProviderRetryPolicy`, `FallbackClaimExtractor`).

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
Ola integrada: 3 (Task 7) sobre las olas 0–2 (Tasks 0–6)
Task 7: PR #11, head bde19f9, merge 9886202, run #124 SUCCESS
Base SHA de la ola 4: 9886202996108f43f6c8099383d49bce49bab429
Pruebas: testDebugUnitTest + lintDebug + assembleDebug + assembleDebugAndroidTest
Resultado: verde; router secuencial, política de reintentos y fallback local integrados
Riesgos pendientes: componer router+UI en el coordinator (Task 8) y release+prueba física (Task 9)
Próxima ola habilitada: 4 (Task 8: integración y UI; luego Task 9: release)
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
- `b08e85066663a67b91ccbf64464ba845b1cb73d6`: PR #9 (Task 5) integrado después de run #117 verde.
- `a9812c019cf6fedc53ddf89c508bed918268980e`: PR #10 (Task 6) integrado después de run #119 verde. Cierra la ola 2.
- `9886202996108f43f6c8099383d49bce49bab429`: PR #11 (Task 7) integrado después de run #124 verde. Cierra la ola 3.

## Condición de cierre

La Fase 5 no se declara completa hasta tener CI verde, APK exacto, escaneo de secretos, migraciones verificadas y prueba física satisfactoria de Gemini, fallback remoto y fallback local en el Moto g max.

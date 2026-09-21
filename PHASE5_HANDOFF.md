# Fase 5: estado y continuidad

Última actualización: 2026-09-21

## Candidato 0.8.0 — segunda pasada editorial

- Rama: `feature/second-pass-editorial-report`.
- Base: `3b68e36` (diseño y plan aprobados).
- Head de implementación: `22db921`.
- Versión: `0.8.0-editorial-pass`, `versionCode 15`.
- Código fuente: Tasks 1–9 implementadas. La segunda IA recibe únicamente claims aceptados,
  devuelve Resumen / Material trabajado / Tarea con referencias auditables, y el cliente conserva
  literalmente la prosa validada. Una corrección invalida el reporte; aprobar exige `READY` y copia
  el informe permanente antes de borrar temporales.
- Persistencia: Room 9 y migración 8→9. El esquema `9.json` fue generado mecánicamente porque KSP
  no pudo ejecutarse; su `identityHash` es provisional y debe regenerarse con KSP antes del release.
- Verificación intentada: pruebas específicas de cada tarea y
  `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
- Resultado: BLOQUEADA antes de compilación. El wrapper intenta descargar Gradle 8.13 desde
  `services.gradle.org` y falla con `java.net.SocketException: Network is unreachable`. Por ello no
  hay conteo de tests, lint, APK ni APK de instrumentación verificables en este entorno.
- Escaneo de secretos: sin credenciales reales; las coincidencias son patrones documentales o claves
  ficticias de tests. No se generó ningún APK para inspeccionar.
- Prueba física: PENDIENTE según `SECOND_PASS_REPORT_DEVICE_TEST.md`; no declarar la versión final.
- Siguiente acción exacta: en un entorno con Gradle/dependencias disponibles, regenerar el esquema 9,
  ejecutar la verificación completa, registrar SHA de los APK y completar el protocolo en Moto g max.

## Estado actual

La Fase 5.1 está integrada en `main` como `0.5.1-free-router` (versionCode 8) mediante PR #14 y commit `63c4be6`. Incluye el router gratuito, las mejoras de velocidad/estado y la ficha combinada de páginas y ejercicios.

La entrega `0.5.2-integrity` (versionCode 9) está armada en la rama de integración `feature/phase5-2-quality` con las tareas I1–I8 integradas y verdes en CI: contratos de identidad y estado (I1), cronología y presupuesto de paquetes (I2), materialización por estado (I3), persistencia y migración Room 5→6 (I4), runtime acotado con fallas semánticas aisladas (I5), gate de evaluación de integridad (I6), reproyección local del cambio de modo (I7), journal cableado con estado semántico observable y "Continuar local" (I7b) e integración/release (I8).

La prueba física en el Moto g max se realizó con resultado satisfactorio (2026-09-16, APK `v0.5.2-integrity-rc1`, versionCode 9). Con CI verde y prueba de dispositivo registrada, `0.5.2-integrity` queda habilitada para integrarse a `main` mediante el PR integrador `feature/phase5-2-quality` → `main`.

La Fase 4 permanece completa, validada por CI y probada con éxito en el Moto g max. La rama de Fase 5 parte de esa base funcional y no debe alterar el motor Whisper local.

## Fuente de verdad

- Repositorio: `ravacholin/Diarioele`
- Base estable: `main@63c4be6cfe5c07f42dd91d8f6948b194e276da56`
- Rama de integración: `feature/phase5-2-quality`
- PR de diseño y planes: `#15`
- Diseño vigente: `docs/superpowers/specs/2026-09-15-phase5-2-quality-design.md`
- Plan de integridad: `docs/superpowers/plans/2026-09-15-phase5-2-integrity.md`
- Plan de calidad: `docs/superpowers/plans/2026-09-15-phase6-quality-loop.md`
- Coordinación vigente: `PHASE5_2_AGENT_COLLABORATION.md`
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
| 8. Integración y UI | COMPLETA | Task 7 | PR #12, run #130 verde, `5fbb38d` → merge `695115a` |
| 9. Feedback y release | CÓDIGO COMPLETO | Task 8 | PR #13, run #134 verde, `ab5caa7` → merge `5ac8132`; falta prueba física |

## Siguiente acción exacta

Ejecutar I0 y luego I1 desde el último head verde de `feature/phase5-2-quality`. Cada tarea usa su rama y abre PR contra la integración. Después de I1 verde se habilitan I2–I6 según `PHASE5_2_AGENT_COLLABORATION.md`.

## Estado histórico de la Fase 5 inicial

Las **Tasks 1–8** están integradas y verdes. Solo resta **Task 9** (release + prueba física), en la rama:

```text
Task 9 -> feature/phase5-task-09-release
```

Task 9 (parte de código, lista tras integrar esta rama):

- ✅ `versionCode` 6→7 y `versionName` `0.5.0-free-router`.
- ✅ `PHASE5_FREE_INFERENCE_DEVICE_TEST.md` con el protocolo, ajustado a lo testeable con la UI actual (Gemini/Groq/OpenRouter exitosos, fallback local por modo avión, cambio de modo sin red, reapertura con caché, edición/aprobación/limpieza, clave inválida sin exposición).
- La CI genera el APK **debug** `0.5.0-free-router` como artefacto (como en Fase 4); sirve para la prueba física.
- **Cierre empírico (pendiente, del usuario):** instalar el APK en el Moto g max, cargar una clave de Gemini de un proyecto sin facturación y completar el protocolo. **Requiere el teléfono.** No se declara la fase completa sin esa prueba.

Pendiente menor declarado para una iteración futura: el **hook de fallos simulados en debug** (para encadenar Gemini 429→Groq→OpenRouter sin quitar la red) y el **panel de estado de ejecución** (procedencia `MIXTO/LOCAL`, `REINTENTAR`/`CONTINUAR LOCAL`). El encadenamiento real solo se fuerza hoy con modo avión o clave inválida.

Nota de alcance ya entregado en Task 8: la UI Compose se validó por compilación y por los tests del controlador (`ProviderSettingsControllerTest`); su aspecto y comportamiento visual se confirman recién en la prueba física. Quedó pendiente (menor) un panel de estado de ejecución con la procedencia `MIXTO/LOCAL` y `REINTENTAR`/`CONTINUAR LOCAL`, a ajustar con el dispositivo.

Pendiente menor declarado: la interpretación remota es reanudable **vía caché** (una respuesta validada por paquete sobrevive a reaperturas); no se agregó un checkpoint de interpretación por paquete adicional porque la caché ya cubre la reanudación.

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
Entrega integrada: Fase 6 (quality loop) Q0–Q7 → 0.6.0-quality-loop (versionCode 10)
Rama: claude/fase-6-quality-loop-hq4oex; PR contra main (#26)
Q0 contratos del quality loop; Q1 grounding + confianza efectiva (quality-score-v1);
Q2 esquemas nativos + prompts de reparación + preflight de facturación OpenRouter;
Q3 fusión híbrida local+remota con procedencia; Q4 revisión estructurada + migración 6→7;
Q5 observabilidad saneada + reintento/continuar local; Q6 corpus local opt-in (JSONL, SAF);
Q7 integración: señales de marcadores, EvaluationSummary, corpus reachable, release.
Pruebas: testDebugUnitTest + lintDebug + assembleDebug + assembleDebugAndroidTest
Resultado: verde. Sin red ni credenciales en las pruebas.
Riesgos pendientes: prueba física en el Moto g max (PHASE6_QUALITY_DEVICE_TEST.md) y la UI
                    Compose fina de revisión/corpus/observabilidad (backend cableado y probado).
Próxima tarea habilitada: benchmark físico 0.6.0-quality-loop antes de integrar a main.
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
- `695115aabc6ea3185ac74a907ed25ae6e910d281`: PR #12 (Task 8) integrado después de run #130 verde. Solo resta Task 9.
- `5ac81325672825339feb9a068f898e3439df7847`: PR #13 (Task 9 código: versión `0.5.0-free-router` + protocolo) integrado después de run #134 verde. Fase 5 completa a nivel de código; resta la prueba física.

## Condición de cierre

La Fase 5 no se declara completa hasta tener CI verde, APK exacto, escaneo de secretos, migraciones verificadas y prueba física satisfactoria de Gemini, fallback remoto y fallback local en el Moto g max.

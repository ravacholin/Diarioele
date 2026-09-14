# Fase 5: estado y continuidad

Última actualización: 2026-09-14

## Estado actual

La Fase 5 está diseñada y preparada para implementación colaborativa. Todavía no se implementó código de producción de esta fase.

La Fase 4 permanece completa, validada por CI y probada con éxito en el Moto g max. La rama de Fase 5 parte de esa base funcional y no debe alterar el motor Whisper local.

## Fuente de verdad

- Repositorio: `ravacholin/Diarioele`
- Base estable: `feature/phase4-whisper-recovery`
- Rama de integración: `feature/phase5-contextual-interpretation`
- Head documental inicial de Fase 5: `d4bf31be623e7d6bacb8bb42fb71ebf065f45d37`
- Diseño: `docs/superpowers/specs/2026-09-14-contextual-interpretation-design.md`
- Plan: `docs/superpowers/plans/2026-09-14-contextual-interpretation.md`
- Coordinación multiagente: `PHASE5_AGENT_COLLABORATION.md`

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
| 1. Contratos y escenarios | PENDIENTE | ninguna | sin PR |
| 2. Catálogo y credenciales | BLOQUEADA | Task 1 | sin PR |
| 3. Paquetes contextuales | BLOQUEADA | Task 1 | sin PR |
| 4. Prompt y clientes HTTP | BLOQUEADA | Task 1 | sin PR |
| 5. Validación y reducción | BLOQUEADA | Tasks 3 y 4 | sin PR |
| 6. Caché Room | BLOQUEADA | Task 1 | sin PR |
| 7. Router y fallback | BLOQUEADA | Tasks 2, 4, 5 y 6 | sin PR |
| 8. Integración y UI | BLOQUEADA | Task 7 | sin PR |
| 9. Feedback y release | BLOQUEADA | Task 8 | sin PR |

## Siguiente acción exacta

Ejecutar solamente Task 1 del plan en:

```text
feature/phase5-task-01-contracts
```

Secuencia:

1. crear la rama desde el último head verde de `feature/phase5-contextual-interpretation`;
2. escribir primero `InferenceContractTest` y escenarios sintéticos;
3. implementar modelos e interfaz comunes mínimos;
4. ejecutar las pruebas indicadas en Task 1;
5. escanear secretos;
6. subir la rama;
7. abrir PR contra `feature/phase5-contextual-interpretation`;
8. solicitar revisión de especificación y calidad;
9. integrar solo después de CI verde;
10. actualizar este handoff.

No iniciar Tasks 2, 3, 4 o 6 antes de congelar el contrato de Task 1.

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

Después de cada integración, reemplazar esta sección con:

```text
Task integrada:
PR:
Base SHA:
Head SHA:
Workflow:
Pruebas:
Resultado:
Riesgos pendientes:
Próxima tarea habilitada:
```

No marcar una tarea como completa basándose solamente en el reporte de un agente.

## Historial documental

- `d4bf31be623e7d6bacb8bb42fb71ebf065f45d37`: diseño y plan del router gratuito.
- `4fbc960666826f98ada81e9de60fdf045736c865`: protocolo de colaboración multiagente.

## Condición de cierre

La Fase 5 no se declara completa hasta tener CI verde, APK exacto, escaneo de secretos, migraciones verificadas y prueba física satisfactoria de Gemini, fallback remoto y fallback local en el Moto g max.

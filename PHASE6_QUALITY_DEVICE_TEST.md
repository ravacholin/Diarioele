# Protocolo de prueba física — 0.6.0-quality-loop (Moto g max)

Este protocolo valida en el teléfono la Fase 6 (quality loop). No se declara completa la
entrega hasta tener CI verde, APK exacto, escaneo de secretos sin hallazgos, migraciones
verificadas y esta prueba física registrada. El audio nunca sale del dispositivo.

## Preparación

- Instalar el APK debug `0.6.0-quality-loop` (versionCode 10).
- Abrir la app una vez para forzar la migración Room 6→7 sobre una base con datos previos
  (verificar que la ficha existente conserva sus campos editados).
- Confirmar que la inferencia remota está desactivada por defecto y que cada proveedor exige
  consentimiento y credencial independientes.

## Casos

1. **Sesión corta (10 s)**: grabar, finalizar, generar la ficha. La notificación muestra
   "GENERANDO LA FICHA" mientras interpreta, nunca "TRANSCRIBIENDO".
2. **Transcripción larga (90 min, sintética o importada)**: procesar hasta completar.
3. **Fallback local sin red**: modo avión; la ficha se genera solo con lo local.
4. **Acierto de caché**: reabrir/cambiar de modo no vuelve a llamar a la red.
5. **429 / cuota**: el router corta el proveedor y sigue con el siguiente o el fallback.
6. **Timeout**: el paquete cae a local sin colgar la generación.
7. **JSON inválido**: se pide una única reparación; si sigue inválido, pasa de proveedor.
8. **Alucinación numérica** (página/ejercicio inexistente): el gate marca
   NUMERIC_EVIDENCE_MISMATCH y no la acepta; la página local con evidencia sobrevive.
9. **Reparación**: una respuesta REPAIR dispara el segundo intento con los códigos de issue.
10. **Continuar local**: el docente fuerza el fallback local desde la UI.
11. **Pausa / reanudar** la interpretación.
12. **Kill / restart** durante la interpretación: se reanuda sin perder audio ni checkpoints.
13. **Aceptar / rechazar / corregir** un claim "por confirmar"; la corrección protege solo su
    campo y queda registrada como revisión.
14. **Guardar ejemplo (preview)**: revisar el ejemplo anonimizado y guardarlo en el corpus
    local; confirmar que no contiene audio, id de sesión ni fechas.
15. **Exportar JSONL** por Storage Access Framework y **borrar todo** el corpus.

## Registro

Al terminar, anotar acá el modelo, la versión de Android, el resultado de cada caso y
cualquier error observado. Cada error se convierte en el escenario sintético mínimo que lo
reproduzca en `SemanticIntegrityEvaluationTest` o `SemanticQualityReportTest`.

- Dispositivo:
- Android:
- Fecha:
- Resultado por caso (1–15):
- Observaciones:

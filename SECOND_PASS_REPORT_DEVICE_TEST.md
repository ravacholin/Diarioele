# Prueba física — segunda pasada editorial

Versión objetivo: `0.8.0-editorial-pass` (`versionCode 15`)

Dispositivo: Moto g max

Estado inicial: instalar el APK debug limpio, configurar como máximo una clave de un proyecto sin facturación y conservar el audio hasta aprobar.

No declarar esta versión final hasta completar esta hoja y registrar el APK y SHA exactos.

## Preparación

- [ ] Confirmar versión `0.8.0-editorial-pass` en la aplicación instalada.
- [ ] Confirmar que la inferencia remota está desactivada hasta dar consentimiento.
- [ ] Activar un proveedor gratuito y probar la conexión.
- [ ] Verificar que no se envía audio: solo texto transcripto con identificadores artificiales.

## Tres grabaciones reales

Realizar una grabación por escenario. Antes de aprobar, comparar la ficha final con “Evidencia aceptada” y “Comparar con ficha anterior”.

| Grabación | Dictado mínimo | Resultado esperado |
|---|---|---|
| A — realizado/asignado | “Página 42, ejercicio 3. El 4 queda para casa.” | Material: página 42 y ejercicio 3. Tarea: ejercicio 4. |
| B — rango/corrección | “Páginas 47 a 49, ejercicios 2 y 5. Perdón, el 6, no el 5.” | Conserva 47–49 y 2/6; no repite ni deja 5 activo. |
| C — tarea indirecta/ruido | “Terminamos la lectura. Para mañana, escribir el final. Recuerden la reunión.” | La escritura aparece en Tarea; el recordatorio administrativo no aparece como trabajo. |

Para cada una:

- [ ] El Resumen es fiel y breve.
- [ ] Material trabajado conserva literalmente todos los números relevantes.
- [ ] Tarea conserva la asignación y no incluye lo ya realizado.
- [ ] Cada bloque permite abrir las fuentes y estas coinciden con la evidencia.
- [ ] No hay omisiones, repeticiones ni asociaciones página–ejercicio incorrectas.
- [ ] “Copiar ficha final” reproduce exactamente las tres secciones visibles.

## Corrección y regeneración

- [ ] En una evidencia “Por confirmar”, corregir un ejercicio (por ejemplo, 3 → 4).
- [ ] Confirmar que la ficha anterior queda `DESACTUALIZADA` y no se puede aprobar.
- [ ] Pulsar `REGENERAR FICHA FINAL`.
- [ ] Confirmar que cambia el dato corregido, conserva los demás y vuelve a estado listo.
- [ ] Confirmar que no se vuelve a ejecutar Whisper ni se requiere grabar otra vez.

## Sin red y reapertura

- [ ] Con una ficha `READY`, activar modo avión, cerrar completamente y reabrir DiarioELE.
- [ ] Confirmar que la ficha validada sigue visible y se puede copiar sin una llamada nueva.
- [ ] Con una ficha aún no generada, intentar generar sin red: debe fallar sin borrar audio, transcripción ni evidencia.
- [ ] Restaurar red y regenerar; debe recuperarse sin repetir la transcripción.

## Aprobación y limpieza

- [ ] Aprobar una ficha `READY` y confirmar el diálogo de eliminación.
- [ ] Confirmar que el archivo permanente muestra Resumen, Material trabajado y Tarea.
- [ ] Confirmar que solo fecha y nivel son editables; el informe auditado no lo es.
- [ ] Reiniciar la aplicación y abrir el diario sin red.
- [ ] Confirmar que copiar/buscar siguen funcionando y que audio, transcripción y evidencia temporal ya no están.
- [ ] Confirmar que una ficha `FAILED`, `STALE` o `GENERATING` nunca permite aprobar.

## Registro de defectos

| Escenario | Omisiones | Repeticiones | Número alterado | Asociación incorrecta | Estado / notas |
|---|---:|---:|---:|---:|---|
| A |  |  |  |  |  |
| B |  |  |  |  |  |
| C |  |  |  |  |  |
| Corrección |  |  |  |  |  |
| Reapertura sin red |  |  |  |  |  |

## Cierre

- APK probado:
- SHA-256 del APK:
- Commit:
- Proveedor/modelo:
- Fecha:
- Resultado: PENDIENTE

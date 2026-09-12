# Diseño aprobado de fase 3

## Resultado de la fase

La fase 3 completa el flujo seguro de un diario diario: revisar la ficha generada, editarla, aprobarla, guardarla de forma permanente, comprobar la lectura exacta de lo guardado y recién entonces borrar el audio, la transcripción, la evidencia y el borrador temporales.

## Reglas vinculantes

- Android únicamente, uso personal y funcionamiento local, sin cuenta ni backend.
- Se mantiene `com.capo.diarioclase.phase2` para actualizar la app paralela instalada.
- No se solicita permiso de Internet.
- Existe un solo diario por sesión/día, formado por bloques pausables.
- La ficha permanente contiene campos separados: temas, actividades realizadas, páginas, ejercicios hechos y tarea.
- La ficha puede editarse antes de aprobarse y también después de archivarse.
- Las ediciones manuales no deben perderse al cambiar el modo de interpretación.
- El modo de interpretación es un ajuste persistente: Conservador, Equilibrado o Exhaustivo; Conservador es el valor inicial.
- La interpretación siempre se apoya en evidencia de la transcripción y no inventa datos.
- Guardar y releer exactamente la ficha permanente ocurre antes de cualquier borrado.
- Si un borrado falla, la ficha permanente se conserva y la sesión queda en `CLEANUP_PENDING` para reintentar.
- El borrado es idempotente: reintentar no duplica el diario ni daña la ficha.
- El archivo permite buscar por fecha, nivel y los cinco campos, abrir, editar, copiar y eliminar una ficha con confirmación.
- Copiar al portapapeles omite campos vacíos y metadatos técnicos.
- Tras aprobar, la interfaz debe mostrar claramente si el diario quedó archivado y si los temporales se eliminaron o aún requieren reintento.
- El estilo permanece minimalista brutalista moderno, elegante, oscuro, monocromo, rectangular y sin emoji.
- La fase no agrega nube, sincronización, audio permanente ni notas de confirmación separadas.
- Los archivos Markdown permanecen en la raíz; no se crea `docs`.

## Criterio de seguridad central

Nunca se elimina audio, transcripción, evidencia ni borrador por el simple hecho de finalizar o transcribir. El borrado solo puede comenzar después de una aprobación explícita del usuario y una verificación exacta de la ficha permanente recuperada desde Room.

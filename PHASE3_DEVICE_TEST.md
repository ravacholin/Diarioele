# Prueba empírica de fase 3

Esta prueba valida el flujo local de la fase 3 en un teléfono real. No usa nube ni requiere conexión de red.

## Preparación segura

1. **No desinstales la aplicación.** Conserva sus datos y su identidad de actualización.
2. Instala la compilación de fase 3 **encima de la aplicación existente `Diario de clase 2`**. Confirma que se actualiza la misma aplicación; no debe instalarse una segunda app.
3. Concede el permiso de micrófono cuando Android lo solicite. Ten disponible reproducción de audio en el teléfono.

## Primero: regresión de transcripción de 10 segundos

Antes de probar cualquier otro recorrido, realiza una grabación sintética nueva y corta: aproximadamente **10 segundos** de voz clara. Di una frase que contenga los cinco datos, por ejemplo: “Hoy vimos el pretérito, hicimos una lectura, página doce, ejercicio tres y para tarea escribir cinco frases.” Finaliza el día e inicia el procesamiento local.

El procesamiento normal debe terminar con rapidez. Para esta grabación de 10 segundos, antes de 25 segundos debe ocurrir exactamente una de estas opciones:

- aparece una transcripción/ficha para revisar; o
- aparece un fallo específico y seguro (por ejemplo, que el motor local no responde, no admite el audio, falta el idioma local o no detectó voz).

Nunca debe quedar girando indefinidamente. Si aparece un fallo, comprueba que el audio todavía se puede reproducir y que la opción de reintentar el procesamiento continúa disponible. No apruebes ni continúes con limpieza en el recorrido de fallo.

## Recorrido completo después de transcribir correctamente

Solo si la transcripción terminó correctamente, continúa con esa misma grabación:

1. Edita los cinco campos: temas, actividades realizadas, páginas, ejercicios hechos y tarea. Guarda los cambios si la pantalla lo solicita.
2. Aprueba la ficha explícitamente. Espera el resultado de archivado.
3. Comprueba que el audio temporal ya no ofrece reproducción en la pantalla de captura. Si se muestra `LIMPIEZA PENDIENTE`, no se considera una limpieza completa: verifica que la ficha permanente sigue visible y usa únicamente `REINTENTAR LIMPIEZA` cuando quieras repetir el borrado.
4. Abre `DIARIOS GUARDADOS`. Busca una palabra con acento escrita sin acento (por ejemplo, busca `narracion` si el tema contiene `narración`) y confirma que aparece la ficha.
5. Abre la ficha archivada, edítala nuevamente y guarda. Cierra y vuelve a abrirla para confirmar que el cambio persiste.
6. Copia la ficha y pega o inspecciona el portapapeles. Debe contener los campos no vacíos y no debe incluir metadatos técnicos de la aplicación.

## Comprobación de recuperación sin borrado automático

Si se muestra `LIMPIEZA PENDIENTE`, cierra y vuelve a abrir la aplicación antes de tocar `REINTENTAR LIMPIEZA`. Debe volver a mostrarse la ficha permanente con la limpieza pendiente; el inicio no debe borrar audio ni filas temporales automáticamente. El borrado solo puede ejecutarse desde el reintento visible que elige la persona usuaria.

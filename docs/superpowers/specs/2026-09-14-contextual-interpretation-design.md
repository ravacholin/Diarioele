# Diseño de Fase 5: interpretación contextual offline

Fecha: 2026-09-14

## Problema

Whisper local produce una transcripción suficientemente útil, pero la ficha diaria pierde información o la clasifica mal. La causa está en la interpretación posterior: cada `TranscriptSpan` se examina de forma aislada mediante expresiones regulares, las referencias se vinculan solo con una página activa simple, las correcciones orales no se resuelven y la confianza se asigna con constantes.

La Fase 5 debe reconstruir eventos pedagógicos a partir de varios fragmentos consecutivos sin modificar el motor Whisper ni exigir un corpus previo de grabaciones reales.

## Objetivo

Transformar una transcripción española en cinco campos confiables:

- Temas.
- Actividades realizadas.
- Páginas.
- Ejercicios hechos.
- Tarea.

Cada resultado debe conservar evidencia temporal y distinguir si fue realizado, asignado, propuesto, cancelado, corregido o incierto.

## Restricciones

- Android, Kotlin y Jetpack Compose.
- Funcionamiento completamente local y sin permiso de Internet.
- Sin nube, APIs pagas, LLM ni descarga de modelos adicionales.
- Whisper `base` en español continúa sin cambios.
- El modo de interpretación no provoca una nueva transcripción.
- `CONSERVATIVE` sigue siendo el modo predeterminado.
- Los campos editados por el usuario nunca se sobrescriben.
- Audio y datos temporales se eliminan solamente después de guardar y aprobar explícitamente.
- La transcripción original no se reescribe ni se limpia destructivamente.
- No se necesita un corpus inicial de grabaciones reales.
- La rama validada de Fase 4 permanece como punto de recuperación.

## Arquitectura

La interpretación se divide en unidades puras y comprobables:

1. `ContextualUtteranceAssembler` agrupa spans vecinos del mismo bloque sin perder timestamps ni texto original.
2. `ConversationalNormalizer` divide el habla en cláusulas y anota negación, pregunta, planificación, autocorrección y cancelación. No borra material.
3. `BookReferenceParser` reconoce páginas, ejercicios, listas, rangos, sufijos y referencias elípticas.
4. `PedagogicalEventExtractor` crea eventos tipados y conserva toda la evidencia que los originó.
5. `ContextualClaimReducer` mantiene el estado pedagógico del bloque, vincula páginas y ejercicios y aplica correcciones posteriores.
6. `EvidenceConfidenceScorer` calcula confianza a partir de señales observables.
7. `InterpretationProjector` conserva los tres modos y decide qué aceptar, confirmar u ocultar.
8. Los marcadores manuales opcionales aportan anclas temporales, pero la interpretación funciona sin ellos.

## Estrategia de pruebas sin corpus

La suite inicial será sintética y declarativa. Cada escenario tendrá:

- spans de entrada con bloque y timestamps;
- eventos esperados;
- claims finales esperados;
- elementos que no deben aparecer.

Los escenarios se escriben a partir de patrones reales del español docente, no de audios del usuario. Una pequeña fábrica combinará verbos, personas, números, listas y marcadores discursivos para multiplicar la cobertura sin mantener cientos de cadenas copiadas.

La matriz mínima incluye:

- enunciados directos;
- información distribuida entre varios spans;
- números en cifras y palabras;
- listas, rangos y sufijos;
- página activa y cambio de página;
- elipsis como “el siguiente” y “ese mismo”;
- preguntas y citas que no deben registrarse;
- planes futuros frente a acciones realizadas;
- tarea asignada, modificada y cancelada;
- autocorrecciones con “no”, “perdón”, “mejor”, “quise decir”, “en realidad” y “bah”;
- repetición pedagógica legítima;
- duplicación producida por ventanas Whisper;
- referencias ambiguas que deben quedar por confirmar.

Esta suite es la especificación inicial. Los ejemplos reales que aparezcan después de usar la aplicación se agregan como nuevas regresiones, pero no son una condición de entrada.

## Semántica de correcciones

Las cláusulas se procesan en orden temporal. Una cláusula posterior puede:

- reemplazar un valor anterior;
- cambiar `PROPOSED` por `PERFORMED`;
- mover un ejercicio de realizado a tarea;
- cancelar una asignación;
- corregir una página o un número;
- dejar dos interpretaciones en estado `UNCERTAIN` si falta un referente inequívoco.

Una negación no elimina automáticamente todo lo anterior. Su alcance se limita al evento o referente enlazado.

Ejemplo:

`Vamos a hacer el 3 y el 4. El 4 no, perdón, queda para casa.`

Resultado:

- ejercicio 3: `PERFORMED`;
- ejercicio 4: `ASSIGNED` como tarea;
- la afirmación previa sobre el ejercicio 4 queda inactiva con estado `CORRECTED`.

## Confianza

La confianza deja de estar fijada solamente por categoría. Se compone de señales:

- verbo de acción explícito;
- categoría nombrada explícitamente;
- número o referencia resuelta;
- página vinculada;
- marcador manual cercano;
- continuidad dentro del mismo bloque;
- penalización por elipsis no resuelta;
- penalización por interrogación, cita o conflicto;
- confianza mínima de los spans que aportan evidencia.

El puntaje debe ser determinista y explicable en pruebas. Los modos solo aplican umbrales al resultado ya calculado.

## Marcadores manuales

Se incorporan anclas opcionales `HOMEWORK`, `PAGE`, `EXERCISE` e `IMPORTANT_ACTIVITY`. Cada marcador guarda sesión, bloque y timestamp. El intérprete busca evidencia en una ventana temporal limitada alrededor del marcador y aumenta la confianza de una categoría compatible.

Los marcadores no crean contenido sin evidencia textual y no son obligatorios para obtener una ficha.

## Aprendizaje progresivo sin entrenamiento

Cuando el usuario edita una ficha, se guarda localmente un registro que contiene:

- valores generados antes de editar;
- valores aprobados;
- ids de evidencia relacionados;
- modo de interpretación;
- fecha.

En esta fase el registro no entrena modelos ni modifica automáticamente reglas. Sirve para reproducir fallos, crear nuevas pruebas y medir qué categorías necesitan ajustes. No sale del dispositivo salvo exportación explícita futura.

## Exclusiones

No forman parte de esta fase:

- cambiar o reentrenar Whisper;
- incorporar un LLM local;
- crear un clasificador aprendido;
- subir audio, transcripciones o correcciones;
- inferir contenido sin evidencia;
- resumir toda la conversación de la clase;
- distinguir automáticamente identidades de hablantes.

Un clasificador pequeño podrá evaluarse en una fase posterior solamente si la suite determinista y la validación física muestran una familia persistente de errores que no pueda resolverse con reglas contextuales.

## Criterios de aceptación

- Todos los escenarios sintéticos deterministas pasan.
- Preguntas, citas, planes futuros y negaciones no generan falsos realizados.
- Correcciones posteriores prevalecen sin borrar evidencia.
- Listas, rangos y referencias distribuidas se vinculan correctamente.
- Cambiar de modo no vuelve a ejecutar Whisper.
- Los campos editados permanecen intactos.
- Cada claim visible contiene evidencia no vacía y timestamps válidos.
- La app sigue sin permiso de Internet.
- Las pruebas completas, lint y ensamblado Android pasan en GitHub Actions.
- Una prueba física en el Moto g max demuestra mejora sobre frases preparadas que cubren los cinco campos, correcciones y tarea.

# Diseño: ficha final mediante una segunda IA sobre evidencia aceptada

Fecha: 2026-09-21  
Repositorio: `ravacholin/Diarioele`  
Base de diseño: `feature/phase6-task-fix-page-exercise-review@34480a7`

## Propósito

DiarioELE extrae correctamente muchos hechos pedagógicos y los muestra en `EVIDENCIA ACEPTADA`.
El problema aparece después: el materializador local y la ficha estructurada pierden información,
la repiten o asocian mal páginas, ejercicios y tarea.

La evidencia aceptada no será el resultado final. Una segunda llamada a IA recibirá esa evidencia,
la ordenará, eliminará el ruido y redactará una ficha útil sin perder ninguna página, ejercicio o
tarea aceptados.

La salida visible tendrá exactamente tres partes:

1. resumen de la clase;
2. material trabajado, con páginas y ejercicios ordenados;
3. tarea completa y separada de lo realizado en clase.

## Decisión principal

Se adopta una arquitectura de dos pasadas:

```text
audio
  -> Whisper local
  -> primera interpretación
  -> validación y revisión
  -> evidencia aceptada
  -> segunda IA editorial
  -> verificación local de cobertura
  -> ficha final
```

La primera interpretación sigue siendo responsable de identificar hechos respaldados por la
transcripción. La segunda IA es responsable de seleccionar, ordenar, relacionar y redactar esos
hechos.

El código local no recompone páginas y ejercicios, no resume por reglas y no vuelve a interpretar
el texto. Solo controla el contrato, la procedencia y la cobertura de los elementos enviados.

## Alcance

La prueba:

- conserva Whisper local, ventanas, checkpoints y deduplicación;
- conserva el router gratuito Gemini, Groq, OpenRouter Free y fallback local;
- conserva validación, quality gate, fusión híbrida y los tres modos de interpretación;
- conserva `EVIDENCIA ACEPTADA`, `POR CONFIRMAR` y las acciones Aceptar, Rechazar y Corregir;
- agrega una segunda etapa remota que trabaja únicamente sobre evidencia ya aceptada;
- muestra y archiva la ficha redactada por esa segunda etapa;
- conserva provisionalmente la ficha anterior para comparación durante la prueba;
- no envía audio ni vuelve a enviar la transcripción completa.

## Entrada de la segunda IA

La segunda IA recibe un paquete compacto con todos los claims de `projection.accepted`, ordenados
cronológicamente. Cada elemento contiene:

- `claim_id` interno opaco;
- categoría;
- estado semántico;
- valor aceptado;
- fragmento de evidencia;
- ordinal de bloque, segmento y span;
- procedencia;
- relaciones de supersesión activas, si existen.

También recibe los claims aceptados cuyo origen sea un marcador manual de tarea. Un marcador crudo
contiene solamente una posición temporal y no se envía por sí solo: primero debe haber producido un
claim respaldado y aceptado durante la interpretación inicial.

No recibe:

- audio;
- rutas locales;
- credenciales;
- ids reales de sesión;
- claims rechazados, inactivos o todavía por confirmar;
- el JSON bruto de la primera respuesta remota;
- la transcripción completa fuera de los fragmentos ya aceptados.

## Responsabilidad editorial de la segunda IA

La segunda IA debe:

- producir un resumen breve y fiel de los temas y actividades centrales;
- ordenar páginas y ejercicios de acuerdo con la cronología y las relaciones explícitas de la
  evidencia;
- agrupar ejercicios con una página solamente cuando la evidencia lo respalde;
- distinguir ejercicios realizados de ejercicios asignados;
- conservar todos los números, rangos, letras y detalles aceptados;
- combinar repeticiones reales sin eliminar hechos diferentes;
- excluir comentarios, dudas, ejemplos incidentales, metadiscurso y texto que no aporte al
  resumen, al material o a la tarea;
- no agregar conocimientos externos, explicaciones pedagógicas ni información ausente de la
  entrada.

La segunda IA no decide si un claim es verdadero. Trabaja sobre claims que ya fueron aceptados.
Puede descartar un claim del informe solamente porque sea ruido editorial o duplicado, nunca porque
contradiga una preferencia propia.

## Contrato de salida

La respuesta usa un esquema pequeño y cerrado. El JSON es únicamente un contrato interno para
transportar y auditar el texto de la segunda IA; no se usa para volver a construir la ficha con
reglas locales.

Forma lógica:

```json
{
  "summary": "Resumen redactado de la clase",
  "material": [
    {
      "text": "Página 42, ejercicios 3 y 4.",
      "source_claim_ids": ["claim-page-42", "claim-ex-3", "claim-ex-4"]
    }
  ],
  "homework": [
    {
      "text": "Terminar el ejercicio 5 de la página 43.",
      "source_claim_ids": ["claim-homework-5", "claim-page-43"]
    }
  ],
  "summary_source_claim_ids": ["claim-topic-1", "claim-activity-2"],
  "discarded": [
    {
      "claim_id": "claim-noise-1",
      "reason": "Comentario incidental sin información para la ficha"
    }
  ]
}
```

El texto visible se toma directamente de `summary`, `material[].text` y `homework[].text`. El
cliente no vuelve a parsearlo ni a asociar números.

## Garantía de no pérdida

Una instrucción de prompt no alcanza para garantizar cobertura. Por eso la respuesta debe enlazar
cada fragmento redactado con sus claims de origen.

El validador local comprueba:

- esquema válido y límites de tamaño;
- ausencia de ids desconocidos;
- que cada claim aceptado figure en una salida o en `discarded`;
- que ningún `PAGE`, `EXERCISE` o `HOMEWORK` aceptado quede solamente en `discarded`;
- que todo claim de página o ejercicio esté usado en `material` o `homework` según su estado;
- que todo claim con destino `HOMEWORK` esté usado en `homework`;
- que los números, rangos y letras normalizados de páginas y ejercicios aparezcan en el texto del
  elemento que declara usar esos claims;
- que los claims usados en el resumen estén declarados en `summary_source_claim_ids`;
- que un claim supersedido o inactivo no reaparezca;
- que las listas no estén vacías cuando existen claims relevantes.

Este control verifica cobertura e integridad literal, pero no recompone el contenido. Reutiliza los
valores ya normalizados en los claims y no vuelve a parsear la transcripción. La IA sigue siendo la
autora del orden y del texto final.

La preservación de números se refuerza en el prompt y mediante casos de evaluación. Durante esta
prueba no se agrega otro parser semántico que intente corregir la prosa de la segunda IA.

## Reparación

Si la primera respuesta de la segunda IA omite claims, usa ids incorrectos o incumple el esquema:

1. se realiza una única solicitud correctiva al mismo proveedor;
2. la corrección recibe la respuesta inválida, los ids faltantes y códigos de error sanitizados;
3. se vuelve a validar la respuesta completa;
4. solamente una respuesta válida puede convertirse en ficha final o guardarse en caché.

La reparación nunca recibe audio, credenciales ni información distinta del paquete editorial
original.

## Proveedores, presupuesto y caché

La segunda etapa reutiliza el catálogo, las credenciales, el consentimiento y el transporte ya
existentes. Usa la misma cadena secuencial gratuita:

```text
Gemini -> Groq -> OpenRouter Free
```

Una respuesta válida detiene la cadena. Los modelos pagos siguen prohibidos.

La etapa editorial tiene:

- prompt y esquema versionados independientemente de la primera interpretación;
- presupuesto temporal propio y acotado;
- caché por hash de evidencia aceptada, proveedor, modelo, prompt, esquema y validador;
- invalidación automática cuando el usuario acepta, rechaza o corrige un claim;
- cero nuevas llamadas al cambiar de pantalla o reabrir una ficha ya generada;
- reintento manual explícito para regenerar la redacción.

## Fallo total y funcionamiento local

Si ningún proveedor produce una ficha editorial válida, la aplicación no muestra una ficha
incompleta como si fuera correcta.

En su lugar:

- conserva la evidencia aceptada y el audio;
- informa que no pudo generar la ficha final;
- permite reintentar la segunda etapa;
- ofrece una vista local de respaldo que enumera literalmente la evidencia aceptada, sin resumirla
  ni presentarla como resultado definitivo;
- mantiene accesibles Aceptar, Rechazar y Corregir.

El fallback local garantiza que no se pierdan datos, pero no pretende sustituir la tarea editorial
de la segunda IA.

## Actualización después de la revisión humana

Aceptar, rechazar o corregir cambia la fuente de verdad. Después de cualquiera de esas acciones:

1. la nueva proyección aceptada se guarda de forma atómica;
2. la ficha editorial anterior se marca como obsoleta;
3. se genera un nuevo hash de entrada;
4. la aplicación ejecuta nuevamente la segunda etapa o muestra que la ficha necesita regenerarse;
5. nunca se combinan una evidencia nueva con una ficha editorial vieja.

Durante una llamada en curso, una revisión humana cancela o invalida el resultado anterior. Una
respuesta tardía cuyo hash no coincida con la evidencia actual se descarta.

## Formato visible

La ficha final se muestra así:

```text
RESUMEN
Se trabajó el contraste entre indefinido e imperfecto mediante una conversación sobre viajes y
una corrección grupal.

MATERIAL TRABAJADO
Página 42, ejercicios 3 y 4.
Página 43, ejercicio 2.

TAREA
Terminar el ejercicio 5 de la página 43.
```

Reglas:

- `RESUMEN` siempre aparece cuando hay evidencia suficiente para describir la clase;
- `MATERIAL TRABAJADO` omite únicamente la sección si no hay páginas ni ejercicios aceptados;
- `TAREA` omite únicamente la sección si no existe ninguna tarea aceptada;
- tocar la ficha copia exactamente el texto visible;
- el usuario puede inspeccionar debajo la evidencia que respalda cada fragmento;
- la ficha anterior queda en una sección secundaria de comparación durante la prueba.

## Persistencia y aprobación

Antes de `APROBAR Y BORRAR AUDIO` se persiste:

- texto exacto del resumen;
- elementos redactados de material y tarea;
- ids de origen por elemento;
- claims descartados y sus razones;
- proveedor y modelo;
- versiones de prompt, esquema y validador;
- hash de la evidencia aceptada;
- fecha pedagógica ya existente.

Solo después de verificar esa escritura comienza la limpieza de audio, transcripción y evidencia
temporal.

La pantalla archivada lee la ficha editorial persistida. No la reconstruye ni vuelve a llamar a un
proveedor.

Si la persistencia falla, no se borra nada. Si la ficha se guardó y falla la limpieza,
`CLEANUP_PENDING` muestra la ficha persistida y permite reintentar únicamente la limpieza.

## Comparación experimental

La versión de prueba conserva dos resultados:

1. ficha editorial de la segunda IA;
2. ficha estructurada anterior.

En el Moto g max se compararán con la evidencia aceptada. Para cada sesión se registrarán:

- páginas, ejercicios o tareas omitidos;
- información repetida;
- números alterados;
- asociaciones página-ejercicio incorrectas;
- actividades realizadas enviadas a tarea;
- tareas enviadas a material trabajado;
- ruido conservado indebidamente;
- cantidad de correcciones manuales;
- resultado preferido para copiar y usar.

## Pruebas automáticas

### Contrato editorial

- serialización y decodificación del esquema cerrado;
- rechazo de ids desconocidos o repetidos;
- rechazo de claims aceptados sin cobertura;
- rechazo de páginas, ejercicios o tareas enviados solamente a `discarded`;
- rechazo de una tarea usada exclusivamente en `material`;
- aceptación de temas o actividades descartados con una razón;
- aceptación de un claim usado en más de una sección cuando corresponde;
- límites de longitud, cantidad de elementos e ids.

### Flujo de red

- éxito en el primer proveedor;
- JSON inválido y reparación exitosa;
- respuesta válida pero incompleta y reparación exitosa;
- reparación fallida y avance al siguiente proveedor;
- agotamiento total y vista local de respaldo;
- caché válida sin red;
- invalidación de caché después de aceptar, rechazar o corregir;
- respuesta tardía descartada por hash obsoleto;
- ninguna prueba usa red ni claves reales.

### Integridad y persistencia

- la ficha visible coincide con el texto devuelto por la segunda IA;
- el cliente no recompone ni reordena ese texto;
- aprobar guarda el mismo texto antes de borrar temporales;
- una falla de guardado conserva audio, transcripción y evidencia;
- una falla de limpieza conserva la ficha permanente;
- reabrir una ficha aprobada no realiza llamadas remotas;
- diarios anteriores siguen abriendo después de la migración.

### Escenarios pedagógicos mínimos

- varias páginas con ejercicios intercalados;
- listas y rangos de ejercicios;
- `página cuarenta y siete, ejercicio tres; no, el cuatro`;
- un ejercicio realizado y otro asignado;
- tarea expresada sin la palabra `tarea`;
- la misma página mencionada en varios bloques;
- repeticiones verdaderas frente a duplicados editoriales;
- ruido conversacional mezclado con material válido;
- claims aceptados y por confirmar mezclados;
- una corrección humana que obliga a regenerar la ficha.

## Prueba física

La APK experimental se valida en el Moto g max con al menos tres grabaciones reales:

1. clase breve con una página, varios ejercicios y tarea;
2. clase con varias páginas, correcciones orales y referencias elípticas;
3. clase extensa con información repetida en distintos bloques.

También se prueba:

- segundo pase exitoso;
- segundo pase sin red;
- cambio de evidencia después de aceptar o corregir;
- cierre y reapertura;
- aprobación y limpieza;
- comparación directa contra la ficha anterior.

## Criterios de aceptación

- La ficha final contiene resumen, material trabajado y tarea según la evidencia disponible.
- Toda página, ejercicio y tarea aceptados están enlazados a un fragmento visible apropiado.
- Ningún claim relevante queda únicamente descartado.
- La segunda IA elimina ruido sin inventar información.
- El cliente no vuelve a interpretar ni recomponer la redacción aprobada.
- Una respuesta incompleta no se muestra ni se cachea como éxito.
- Aceptar, rechazar o corregir invalida la ficha anterior.
- Una respuesta producida con evidencia obsoleta nunca reemplaza la ficha actual.
- El texto copiado y archivado coincide exactamente con el texto visible.
- No se borra audio ni evidencia antes de persistir la ficha final.
- La prueba no envía audio ni habilita modelos pagos.
- Los diarios anteriores siguen abriendo.
- En pruebas reales, la ficha editorial presenta menos omisiones, repeticiones y asociaciones
  incorrectas que la ficha actual.

## Exclusiones

No forman parte de esta prueba:

- eliminar la primera interpretación o la evidencia aceptada;
- usar la evidencia aceptada como ficha final sin una segunda IA;
- enviar audio o la transcripción completa en el segundo pase;
- permitir que reglas locales redacten o reorganicen la ficha final;
- una tercera llamada editorial aparte de la reparación única;
- proveedores o modelos pagos;
- entrenamiento local;
- sincronización del corpus;
- retirar inmediatamente la ficha anterior y sus datos.

## Decisión posterior a la prueba

Si la segunda IA produce una ficha consistentemente superior, una especificación posterior podrá
retirar la materialización anterior de la interfaz y simplificar su persistencia. Hasta completar la
prueba física, la implementación anterior se conserva para comparación y recuperación.

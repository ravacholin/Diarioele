# Diseño de Fase 5: interpretación contextual con Gemini

Fecha: 2026-09-14

## Problema

Whisper local produce una transcripción útil, pero el extractor actual analiza cada `TranscriptSpan` de forma aislada mediante expresiones regulares. No comprende bien información distribuida entre frases, listas, referencias elípticas, preguntas, planes futuros, repeticiones ni autocorrecciones.

La Fase 5 debe convertir la transcripción en una ficha pedagógica precisa sin exigir un corpus inicial de grabaciones reales.

## Objetivo

Usar un modelo rápido de Gemini como intérprete semántico principal y conservar en el teléfono las funciones que necesitan ser deterministas:

- preparación y división del contexto;
- validación de evidencia;
- normalización de páginas y ejercicios;
- resolución de duplicados y conflictos;
- proyección conservadora, equilibrada o exhaustiva;
- caché, recuperación y fallback;
- protección de las ediciones del usuario.

## Campos y estados

Los cinco campos permanentes continúan siendo:

- Temas.
- Actividades realizadas.
- Páginas.
- Ejercicios hechos.
- Tarea.

Cada afirmación debe tener una categoría, un valor normalizado, un estado, confianza y evidencia. Estados admitidos: `PERFORMED`, `ASSIGNED`, `PROPOSED`, `CANCELLED`, `CORRECTED` y `UNCERTAIN`.

## Arquitectura

1. Whisper `base` transcribe audio localmente en español.
2. `GeminiInterpretationPacketBuilder` agrupa spans por bloque, conserva ids y timestamps y limita cada paquete.
3. `GeminiPromptFactory` solicita una extracción exhaustiva mediante JSON estructurado.
4. `GeminiInterpretationClient` realiza llamadas de texto con un modelo Flash configurable.
5. `GeminiResponseValidator` rechaza categorías, estados, referencias y evidencias inválidas.
6. `SemanticClaimReducer` combina paquetes, resuelve duplicados y conserva correcciones posteriores.
7. `InterpretationProjector` aplica los tres modos localmente sin nuevas llamadas.
8. `InterpretationCache` reutiliza respuestas cuando el hash del texto, prompt y modelo no cambió.
9. Si Gemini no está configurado, no hay red o se agotan los reintentos, `FallbackClaimExtractor` produce una ficha local básica y editable.

## Decisiones de privacidad

- El audio nunca se envía a Gemini.
- Solo se envía texto transcripto con identificadores artificiales de spans.
- No se envían nombres de archivo, rutas, ids internos de sesión ni metadatos del dispositivo.
- La primera activación muestra que el nivel gratuito de Gemini puede utilizar el contenido enviado para mejorar productos de Google.
- El usuario debe aceptar explícitamente antes de la primera solicitud.
- La función se puede desactivar; Whisper y el fallback siguen disponibles.
- La clave no se incluye en código, recursos, GitHub, APK, logs, Room ni backups.
- En la versión privada, el usuario ingresa la credencial y se guarda cifrada con Android Keystore.
- Si la aplicación se distribuye a terceros, la integración directa se reemplaza por Firebase AI Logic con App Check.
- La app incorpora permiso de Internet exclusivamente para la interpretación Gemini.

## Estrategia de paquetes

La unidad primaria es el bloque de clase. Para evitar solicitudes excesivas:

- ordenar spans por timestamp;
- producir paquetes de hasta 12.000 caracteres;
- cortar preferentemente en pausas de al menos 4.000 ms;
- repetir como contexto los últimos 2 spans del paquete anterior;
- marcar esos spans como `contextOnly` para que Gemini no cree duplicados;
- procesar paquetes secuencialmente para respetar límites gratuitos;
- consolidar respuestas localmente, sin una segunda llamada.

Cada span se representa así:

```text
[S12|00:14:05-00:14:10] Vamos a la página cuarenta y dos.
[S13|00:14:11-00:14:17] Hacemos los ejercicios tres y cuatro.
[S14|00:14:18-00:14:24] El cuatro no, perdón, queda para casa.
```

## Contrato de Gemini

Gemini devuelve exclusivamente JSON conforme al esquema:

```json
{
  "claims": [
    {
      "category": "EXERCISE",
      "value": "3 (p. 42)",
      "normalized_value": "3 (p. 42)",
      "status": "PERFORMED",
      "confidence": 0.96,
      "evidence_span_ids": ["S12", "S13"],
      "supersedes_claim_keys": []
    }
  ]
}
```

Instrucciones centrales del prompt:

- extraer solamente información pedagógica expresada;
- no convertir preguntas, citas o planes futuros en acciones realizadas;
- distinguir ejercicio realizado de tarea;
- aplicar autocorrecciones posteriores;
- no inventar páginas, ejercicios ni temas;
- citar uno o más ids de spans para cada claim;
- usar `UNCERTAIN` cuando el referente no pueda resolverse;
- devolver todas las afirmaciones con evidencia, dejando los modos para la app.

## Validación local

Un claim solo puede avanzar si:

- la categoría y el estado pertenecen a los enums conocidos;
- el valor no está vacío y respeta los límites de longitud;
- todos los ids de evidencia existen en el paquete;
- al menos una evidencia no está marcada solo como contexto;
- la página o el ejercicio aparecen en las evidencias o pueden vincularse a una página explícita del mismo bloque;
- la confianza está entre 0 y 1;
- el número total de claims no supera 100 por paquete;
- ningún campo adicional modifica el modelo local.

Las respuestas inválidas no se reparan silenciosamente. Se registra un código de fallo, se conserva la transcripción y se ofrece reintentar o usar el fallback.

## Modos

Gemini genera una sola interpretación exhaustiva. Los modos se aplican después:

- `CONSERVATIVE`: acepta confianza mínima 0,85; entre 0,60 y 0,85 queda por confirmar.
- `BALANCED`: acepta confianza mínima 0,70; entre 0,40 y 0,70 queda por confirmar.
- `EXHAUSTIVE`: acepta confianza mínima 0,55; entre 0,01 y 0,55 queda por confirmar.

Cambiar de modo nunca retranscribe ni vuelve a llamar a Gemini.

## Caché y consumo

La clave del caché combina:

- SHA-256 del texto exacto del paquete;
- versión del prompt;
- identificador del modelo;
- versión del esquema.

Una respuesta validada se reutiliza en reanudaciones y reproyecciones. Los fallos no se guardan como respuestas válidas. El usuario puede forzar una nueva interpretación mediante una acción explícita.

## Errores y fallback

La integración distingue:

- sin clave;
- consentimiento no otorgado;
- sin red;
- autenticación inválida;
- cuota agotada o `429`;
- servidor no disponible o `500/503`;
- timeout;
- respuesta vacía;
- JSON inválido;
- evidencia inválida.

Para `429`, `500` y `503` se realizan hasta tres intentos con espera de 2, 5 y 12 segundos. El timeout por solicitud es 90 segundos. Después se conserva todo, se muestra el error y se permite reintentar o generar una ficha básica local.

## Seguridad de credenciales

La clave se introduce en Ajustes y se guarda mediante una envoltura de Android Keystore. La UI solo muestra los últimos cuatro caracteres. Existe una acción para probar la conexión y otra para borrar la clave. Ningún test usa una clave real y GitHub Actions utiliza un cliente falso.

## Pruebas sin corpus

La suite inicial usa transcripciones sintéticas y respuestas JSON preparadas. Incluye:

- cinco categorías;
- cifras y números escritos;
- listas y rangos;
- información repartida entre spans;
- tareas asignadas, cambiadas y canceladas;
- preguntas, citas y planes futuros;
- correcciones con “no”, “perdón”, “mejor”, “en realidad” y “bah”;
- duplicados entre paquetes;
- referencias inválidas;
- JSON truncado o con campos extra;
- `429`, `500`, `503`, timeout y falta de red;
- caché y cambio de modo;
- campos editados por el usuario.

No se necesita audio ni material real del usuario para implementar la fase. Los errores físicos futuros se convierten en escenarios sintéticos mínimos y anónimos.

## Exclusiones

No forman parte de esta fase:

- enviar audio a Gemini;
- usar Gemini para transcribir;
- llamadas en tiempo real durante la grabación;
- una segunda llamada para resumir respuestas;
- entrenar un clasificador;
- guardar la clave en el APK;
- subir datos de feedback;
- identificar hablantes;
- exigir marcadores manuales durante la clase.

## Criterios de aceptación

- Gemini recibe solo texto y referencias artificiales.
- Todos los claims visibles tienen evidencia válida.
- Las correcciones posteriores prevalecen.
- Preguntas, citas y planes futuros no generan falsos realizados.
- Una respuesta malformada no corrompe la ficha ni elimina temporales.
- Cambiar de modo no llama a Gemini.
- Reanudar reutiliza caché válido.
- Los campos editados permanecen intactos.
- Sin clave o sin red existe una ficha local básica.
- Ninguna credencial aparece en el repositorio, APK, base de datos, logs o backups.
- Las pruebas, lint y ensamblado pasan en GitHub Actions.
- El APK se prueba físicamente en el Moto g max con éxito, fallo y modo avión.

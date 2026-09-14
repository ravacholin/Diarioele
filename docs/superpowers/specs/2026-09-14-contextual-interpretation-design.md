# Diseño de Fase 5: interpretación contextual con proveedores gratuitos

Fecha: 2026-09-14

## Problema

Whisper local produce una transcripción útil, pero el extractor actual analiza cada `TranscriptSpan` de forma aislada mediante expresiones regulares. No comprende bien información distribuida entre frases, listas, referencias elípticas, preguntas, planes futuros, repeticiones ni autocorrecciones.

La Fase 5 debe convertir la transcripción en una ficha pedagógica precisa sin exigir un corpus inicial y sin depender de un único servicio de inferencia.

## Decisión

Se implementará un router pequeño, predecible y limitado a tres proveedores:

1. Gemini API como proveedor principal.
2. GroqCloud como primer fallback.
3. OpenRouter `openrouter/free` como segundo fallback.
4. Extractor local básico como salida final sin red.

No se incorporan Cloudflare Workers AI, Mistral ni otro modelo Android en esta fase. Cloudflare exige otro esquema de cuenta y autenticación; Mistral duplicaría la función de Groq; un segundo modelo local aumentaría mucho el tamaño y la complejidad del APK.

## Garantía de costo

La aplicación solo admite configuraciones gratuitas predefinidas:

- Gemini: modelo Flash incluido en el nivel gratuito, con una clave de un proyecto sin facturación.
- Groq: modelo admitido en el plan Free.
- OpenRouter: exclusivamente `openrouter/free`; no acepta ids de modelos pagos.
- Local: reglas deterministas sin servicio externo.

La aplicación no agrega tarjetas, habilita facturación ni cambia automáticamente a modelos pagos. Cuando se agota una cuota, pasa al siguiente proveedor o al fallback local.

La aplicación no puede convertir en gratuita una cuenta externa que el usuario haya asociado voluntariamente a facturación. Para una garantía real, cada clave debe provenir de una cuenta o proyecto sin facturación habilitada. La UI lo explica durante la configuración.

## Objetivo

Conseguir inferencia automática aun cuando Gemini devuelva `429`, `500`, `503`, timeout o una respuesta inválida, manteniendo:

- precisión semántica mediante modelos rápidos;
- evidencia verificable;
- funcionamiento sin corpus;
- cero gasto automático;
- configuración razonablemente sencilla;
- continuidad cuando no hay red o ninguna cuota responde.

## Campos y estados

Los cinco campos permanentes continúan siendo:

- Temas.
- Actividades realizadas.
- Páginas.
- Ejercicios hechos.
- Tarea.

Cada afirmación incluye categoría, valor normalizado, estado, confianza, origen y evidencia. Estados admitidos: `PERFORMED`, `ASSIGNED`, `PROPOSED`, `CANCELLED`, `CORRECTED` y `UNCERTAIN`.

## Arquitectura

1. Whisper `base` transcribe audio localmente en español.
2. `InterpretationPacketBuilder` agrupa spans por bloque, conserva ids artificiales y limita cada paquete.
3. `InterpretationPromptFactory` crea un único prompt y un único esquema lógico para todos los proveedores.
4. `FreeInferenceRouter` recorre solo proveedores configurados en orden Gemini, Groq y OpenRouter.
5. Cada adaptador traduce el contrato común al formato HTTP de su proveedor.
6. `SemanticResponseValidator` rechaza categorías, estados, referencias y evidencias inválidas.
7. `SemanticClaimReducer` combina paquetes, resuelve duplicados y conserva correcciones posteriores.
8. `InterpretationProjector` aplica los tres modos localmente sin nuevas llamadas.
9. `InterpretationCache` reutiliza respuestas cuando paquete, prompt, esquema, proveedor y modelo no cambiaron.
10. `FallbackClaimExtractor` genera una ficha local básica si ningún proveedor produce una respuesta válida.

## Política del router

El router opera por paquete y nunca envía un paquete simultáneamente a varios proveedores.

Orden predeterminado:

```text
Gemini -> Groq -> OpenRouter Free -> Local
```

Solo se intenta un proveedor si está habilitado, tiene consentimiento vigente y posee una credencial guardada.

Transiciones:

- `401/403`: deshabilitar ese proveedor para la ejecución y continuar.
- `429`: continuar inmediatamente con el siguiente proveedor.
- `500/503`: realizar un reintento después de 2 segundos; luego continuar.
- timeout: realizar un reintento; luego continuar.
- JSON inválido o evidencia inválida: realizar una segunda solicitud correctiva al mismo proveedor una sola vez; luego continuar.
- éxito validado: guardar caché y no consultar proveedores posteriores.
- todos fallan: ejecutar fallback local.

Esto limita a dos intentos por proveedor y evita cadenas largas de esperas.

## Proveedores seleccionados

### Gemini

- Endpoint propio de Gemini.
- Modelo Flash gratuito definido en configuración de compilación.
- Salida estructurada mediante JSON Schema.
- Clave enviada en `x-goog-api-key`.
- Principal por calidad y porque el usuario ya utiliza Google AI Studio.

### Groq

- Endpoint compatible con OpenAI.
- Modelo inicial: `openai/gpt-oss-20b`.
- `response_format.type = json_schema` con `strict = true`.
- Clave enviada como `Authorization: Bearer`.
- Es el primer fallback porque el plan gratuito actual admite 30 solicitudes por minuto, 1.000 por día y salida estructurada estricta para ese modelo.

### OpenRouter

- Endpoint compatible con OpenAI.
- Modelo fijo: `openrouter/free`.
- Sin fallback interno hacia modelos pagos.
- La salida se considera best effort y siempre pasa por el mismo validador local.
- Se usa en último lugar porque el modelo concreto puede variar y el límite gratuito es menor.

## Privacidad

- El audio nunca se envía.
- Solo se envía texto transcripto con ids artificiales.
- No se envían rutas, ids internos de sesión, nombre del dispositivo ni metadatos personales agregados por la app.
- El usuario acepta por separado cada proveedor antes de habilitarlo.
- La UI enlaza la política de datos de cada servicio.
- El nivel gratuito de Gemini puede usar contenido para mejorar productos de Google.
- La configuración advierte que OpenRouter puede enrutar a diferentes proveedores de modelos.
- Un proveedor deshabilitado nunca recibe datos.
- La ficha indica qué proveedor resolvió cada claim.
- La app puede funcionar solo con uno, dos o tres proveedores configurados.

## Credenciales

Las credenciales se ingresan en Ajustes y se guardan mediante Android Keystore. Se usa una entrada cifrada independiente por proveedor:

- `diarioclase.inference.gemini.v1`
- `diarioclase.inference.groq.v1`
- `diarioclase.inference.openrouter.v1`

Ninguna clave aparece en código, recursos, GitHub, APK, Room, logs, excepciones ni backups. La UI muestra únicamente los últimos cuatro caracteres. Cada proveedor ofrece guardar, probar y borrar clave.

## Paquetes

La unidad primaria es el bloque de clase:

- hasta 12.000 caracteres;
- corte preferente en pausas de al menos 4.000 ms;
- últimos dos spans repetidos como `contextOnly`;
- procesamiento secuencial;
- consolidación local;
- sin detector complejo previo de ambigüedad.

No se implementa un selector de 10 a 30 fragmentos candidatos porque podría omitir temas y actividades sin palabras clave y recrearía el problema del extractor literal. Para el volumen personal, unos pocos paquetes por bloque caben ampliamente en las cuotas seleccionadas y simplifican la arquitectura.

Cada span se representa así:

```text
[B2-S12|00:14:05-00:14:10] Vamos a la página cuarenta y dos.
[B2-S13|00:14:11-00:14:17] Hacemos los ejercicios tres y cuatro.
[B2-S14|00:14:18-00:14:24] El cuatro no, perdón, queda para casa.
```

## Contrato semántico común

Todos los adaptadores deben obtener este objeto, aunque cada API lo envuelva de manera diferente:

```json
{
  "claims": [
    {
      "category": "EXERCISE",
      "value": "3 (p. 42)",
      "normalized_value": "3 (p. 42)",
      "status": "PERFORMED",
      "confidence": 0.96,
      "evidence_span_ids": ["B2-S12", "B2-S13"],
      "supersedes_claim_keys": []
    }
  ]
}
```

El prompt exige:

- extraer solamente información expresada;
- distinguir pregunta, cita, plan, acción realizada y tarea;
- aplicar autocorrecciones posteriores;
- no inventar páginas, ejercicios ni temas;
- citar ids válidos para cada claim;
- usar `UNCERTAIN` cuando falta un referente;
- producir extracción exhaustiva, dejando los modos para la app.

## Validación local

Un claim solo puede avanzar si:

- categoría y estado pertenecen a los enums;
- valor no vacío, máximo 300 caracteres;
- confianza entre 0 y 1;
- todos los ids existen;
- al menos una evidencia no es solo contexto;
- página o ejercicio están respaldados por evidencia o contexto explícito del mismo bloque;
- máximo 100 claims por paquete;
- propiedades adicionales no alteran el modelo local.

La aplicación siempre construye `EvidenceRef` desde los spans locales. Nunca acepta como evidencia un fragmento textual inventado por el proveedor.

## Modos y caché

La primera respuesta válida produce una interpretación exhaustiva. Los modos se aplican localmente:

- `CONSERVATIVE`: acepta desde 0,85; confirma entre 0,60 y 0,85.
- `BALANCED`: acepta desde 0,70; confirma entre 0,40 y 0,70.
- `EXHAUSTIVE`: acepta desde 0,55; confirma entre 0,01 y 0,55.

Cambiar de modo no consume inferencia.

El caché combina SHA-256 del paquete, versión del prompt, versión del esquema, proveedor y modelo. Antes de llamar a un proveedor, el router busca una respuesta válida de cualquiera de los proveedores habilitados para ese mismo paquete. Así una respuesta previa de Gemini evita usar fallbacks al reabrir la sesión.

## Estado visible

La interfaz muestra:

- proveedor principal y fallbacks configurados;
- orden efectivo;
- paquete actual y total;
- proveedor que está respondiendo;
- cuota agotada, autenticación inválida, timeout o respuesta inválida;
- origen `GEMINI`, `GROQ`, `OPENROUTER`, `MIXTO` o `LOCAL`;
- acción de reintentar inferencia;
- acción de continuar con ficha local.

No muestra estimaciones de cuota inventadas. Solo usa encabezados de límite si el proveedor los devuelve.

## Pruebas sin corpus

La suite usa transcripciones sintéticas, clientes falsos y respuestas JSON preparadas. Cubre:

- cinco categorías;
- listas, rangos y autocorrecciones;
- preguntas, citas y planes futuros;
- respuesta válida de cada proveedor;
- Gemini 429 seguido de Groq exitoso;
- Gemini y Groq caídos seguido de OpenRouter exitoso;
- los tres fallan seguido de fallback local;
- credencial ausente o inválida;
- JSON incorrecto;
- evidencia inexistente;
- caché;
- cambio de modo;
- reapertura;
- campos editados;
- garantía de que un éxito detiene la cadena.

Ninguna prueba de CI usa red ni claves reales.

## Exclusiones

No forman parte de esta fase:

- Cloudflare Workers AI;
- Mistral API;
- segundo modelo local;
- servidor propio;
- inferencia paralela;
- selección aprendida de proveedor;
- benchmarking automático de calidad;
- envío de audio;
- inferencia durante la grabación;
- segunda llamada de consolidación;
- identificación de hablantes;
- modelos pagos o cambio automático a modelos pagos.

## Criterios de aceptación

- Gemini es principal; Groq y OpenRouter funcionan como fallbacks reales.
- Solo se consultan proveedores configurados y consentidos.
- Una respuesta válida detiene la cadena.
- Agotar cuotas nunca inicia una operación paga.
- Todos los claims visibles tienen evidencia válida.
- Cambiar de modo no llama a ningún proveedor.
- Reabrir reutiliza caché.
- La procedencia queda registrada por claim.
- Sin ninguna inferencia remota queda una ficha local editable.
- Un fallo nunca elimina audio, transcript ni resultados válidos previos.
- Ninguna credencial aparece en repositorio, APK, Room, logs o backups.
- CI pasa sin acceso a servicios externos.
- El APK se prueba en Moto g max con éxito primario, fallback remoto y fallback local.

## Referencias verificadas

- Groq Free Plan y límites: https://console.groq.com/docs/rate-limits
- Groq Structured Outputs: https://console.groq.com/docs/structured-outputs
- OpenRouter límites gratuitos: https://openrouter.ai/docs/api_reference/limits
- Gemini precios y nivel gratuito: https://ai.google.dev/gemini-api/docs/pricing
- Cloudflare Workers AI pricing, excluido por complejidad: https://developers.cloudflare.com/workers-ai/platform/pricing/
- Mistral Free mode, excluido por redundancia: https://docs.mistral.ai/

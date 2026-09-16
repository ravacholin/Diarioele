# Diseño de Fase 5.2: integridad y ciclo de calidad de la interpretación

Fecha: 2026-09-15  
Repositorio: `ravacholin/Diarioele`  
Base aprobada: `main@63c4be6cfe5c07f42dd91d8f6948b194e276da56`  
Rama de integración: `feature/phase5-2-quality`

## Propósito

DiarioELE ya transcribe localmente con Whisper y dispone de una cadena gratuita de inferencia remota:

```text
Gemini 2.5 Flash -> Groq GPT OSS 20B -> OpenRouter Free -> extractor local
```

La Fase 5.2 debe convertir esa cadena de disponibilidad en un sistema de interpretación pedagógica medible, rápido y corregible. La aplicación debe producir una ficha útil aunque no haya red, conservar evidencia trazable, evitar nuevas llamadas innecesarias y transformar las correcciones del profesor en casos locales de evaluación sin exigir un corpus inicial.

La fase se entregará en dos versiones:

1. `0.5.2-integrity`: corrige errores deterministas y riesgos de estado.
2. `0.6.0-quality-loop`: agrega fusión local + IA, evaluación, feedback y corpus local opcional.

## Estado de partida

La versión `0.5.1-free-router` ya incluye:

- Whisper local y audio que nunca se envía;
- proveedores remotos desactivados por defecto;
- consentimiento y credencial independiente por proveedor;
- catálogo cerrado de modelos gratuitos;
- paquetes textuales con ids artificiales;
- validación estructural y evidencia referenciada;
- caché por sesión, paquete, proveedor, modelo y versiones;
- fallback local;
- modos conservador, equilibrado y exhaustivo aplicados localmente;
- UI de configuración y prueba de conexión;
- separación visual entre transcripción y generación de ficha;
- combinación de páginas y ejercicios.

## Problemas que este diseño resuelve

### Integridad temporal

Los tiempos de Whisper son relativos a cada segmento. El constructor actual vuelve a ordenar spans de un bloque por `startMs`, lo que puede intercalar segmentos consecutivos. La Fase 5.2 preservará el orden de persistencia:

```text
ordinal de bloque -> ordinal de segmento -> ordinal de span
```

`startMs` solo ordenará dentro de un mismo segmento.

### Estado y destino de los claims

La política declarada `StatusFieldPolicy` no se aplica de extremo a extremo. En particular, `EXERCISE + ASSIGNED` puede quedar en Páginas y ejercicios en vez de Tarea.

La materialización de la ficha usará siempre la política estado→campo antes que la categoría:

- `PERFORMED`: campo correspondiente a la categoría;
- `ASSIGNED`: Tarea;
- `UNCERTAIN`: Por confirmar;
- `PROPOSED`, `CANCELLED` y el valor obsoleto de una corrección: historial inactivo.

El valor correcto que reemplaza otro claim usa `PERFORMED` o `ASSIGNED`. `CORRECTED` queda reservado al claim obsoleto o al evento histórico, no al valor nuevo visible.

### Identidad global

`providerClaimKey` solo es único dentro de una respuesta. No se usará como primary key global.

Cada claim tendrá:

- `claimId`: id interno determinista y global;
- `runId`;
- `packetId`;
- `provider`;
- `providerClaimKey`;
- `claimOrdinal`.

El `claimId` se derivará de esos datos. Las supersesiones persistirán mediante `claimId`, nunca mediante una clave libre fuera de su alcance.

### Persistencia de evidencia

Room conservará la relación completa:

```text
sesión -> ejecución -> paquete -> intento -> claim -> evidencias -> revisión humana
```

La evidencia se vinculará con el `transcriptSpanId` local. Los ids públicos enviados a proveedores solo funcionarán como alias del paquete y no reemplazarán la identidad local.

### Calidad frente a mera validez

Una respuesta estructuralmente válida no se considerará automáticamente de buena calidad. Se agregará un `SemanticQualityGate` local que evaluará:

- presencia de claims cuando existen señales literales claras;
- respaldo numérico de páginas y ejercicios;
- coherencia entre `value`, `normalizedValue` y evidencia;
- duplicados y contradicciones;
- dirección temporal de correcciones;
- asociación de ejercicio con página;
- uso de spans no contextuales;
- categorías y estados compatibles.

Una respuesta vacía solo será aceptable si el paquete no contiene señales pedagógicas locales y el evaluador no detecta omisiones evidentes.

La confianza declarada por el modelo será una señal, no una probabilidad. La confianza efectiva combinará:

- confianza del proveedor;
- fuerza de la evidencia;
- coincidencia con reglas locales;
- contradicciones;
- ambigüedad del referente;
- procedencia y validaciones superadas.

Los umbrales de los tres modos se aplicarán sobre esa confianza efectiva.

## Arquitectura elegida

Se adopta una arquitectura híbrida guiada por evidencia.

### Extracción local permanente

El extractor local se ejecuta siempre, no solamente cuando fallan los proveedores. Su responsabilidad será producir candidatos de alta precisión para:

- páginas;
- ejercicios explícitos;
- marcadores manuales de tarea;
- números y rangos normalizables;
- expresiones literales inequívocas.

No intentará resolver elipsis complejas, intención conversacional o correcciones distribuidas.

### Inferencia contextual

La IA resolverá:

- tema frente a ejemplo o cita;
- actividad realizada frente a pregunta;
- tarea expresada indirectamente;
- futuro o propuesta frente a actividad realizada;
- autocorrecciones;
- relaciones entre páginas y ejercicios;
- referentes elípticos;
- incertidumbre.

El prompt tratará la transcripción como datos no confiables delimitados. Las instrucciones pronunciadas dentro de la clase nunca modificarán el contrato de extracción.

Gemini recibirá JSON Schema mediante su configuración nativa. Groq conservará structured outputs estrictos. OpenRouter seguirá como best effort y siempre pasará por la misma validación local.

### Fusión

`HybridClaimMerger` combinará candidatos locales y remotos.

Reglas:

- una coincidencia local y remota refuerza el claim;
- un dato remoto no puede eliminar silenciosamente un dato literal bien respaldado;
- un conflicto inequívoco posterior puede superseder el valor anterior;
- un conflicto no resoluble se convierte en confirmación;
- se conserva procedencia múltiple cuando intervienen reglas e IA;
- el orden final se deriva de la evidencia local, no del orden del JSON devuelto.

### Calidad y reparación

El router distinguirá:

- error de transporte;
- error estructural;
- error semántico;
- respuesta insuficiente;
- cuota o autenticación;
- cancelación;
- deadline agotado.

La segunda solicitud correctiva incluirá un código seguro y una explicación mínima del defecto detectado. Nunca incluirá cuerpos HTTP, claves ni información no presente en el paquete original.

Una respuesta que no supera el quality gate no se cachea como éxito definitivo.

## Rendimiento y estado

La transcripción y la interpretación serán máquinas de estado separadas. Un fallo de IA nunca modificará un segmento `TRANSCRIBED`.

Estados semánticos mínimos:

- `PENDING`;
- `RUNNING`;
- `REMOTE_OK`;
- `LOCAL_OK`;
- `MIXED_OK`;
- `FAILED`;
- `CANCELLED`.

Cada paquete se procesa y persiste como unidad reanudable.

Presupuestos:

- modo avión o ausencia de proveedores: fallback local en menos de 1 s;
- sesión resuelta completamente desde caché: menos de 300 ms de sobrecarga;
- máximo 60 s de red por paquete;
- máximo 120 s de interpretación remota por sesión;
- al agotar el presupuesto, los paquetes restantes continúan localmente;
- cambio de modo: cero llamadas remotas y actualización objetivo menor a 300 ms;
- cancelar o continuar local: interrupción de nuevas llamadas en menos de 1 s.

El router seguirá sin enviar un mismo paquete simultáneamente a varios proveedores. Se permite ejecutar reglas locales mientras se prepara la interpretación porque no consume red ni cuota.

Tras dos fallos transitorios consecutivos de un proveedor, su circuito se abre para los paquetes restantes de la ejecución. `Retry-After` se respeta solo si cabe dentro del deadline.

## Presupuesto de paquetes

El límite se calculará sobre el cuerpo serializado real, incluyendo:

- prompt;
- esquema;
- ids y timestamps;
- delimitadores;
- caracteres escapados;
- spans de solapamiento.

Un span individual demasiado grande se divide de forma segura. Se agregarán pruebas generativas con Unicode, saltos, comillas, pausas y segmentos extensos.

## Persistencia

Se agregan tablas equivalentes a:

### `interpretation_runs`

- id y sesión;
- inicio y fin;
- estado;
- versión de app, Whisper, prompt, esquema y validador;
- hash de transcripción;
- modo usado;
- procedencia final;
- fallo terminal sanitizado.

### `interpretation_packets`

- run;
- ordinal;
- packetId;
- estado;
- timestamps;
- hash y presupuesto;
- resultado final.

### `provider_attempts`

- paquete;
- proveedor y modelo;
- número de intento;
- cache hit;
- outcome tipificado;
- duración;
- timestamp.

No guarda claves, headers, cuerpos HTTP ni texto transcripto.

### `semantic_claims`

- claimId interno;
- paquete e intento;
- providerClaimKey;
- categoría, valor, valor normalizado, estado;
- confianza declarada y efectiva;
- origen y actividad.

### `claim_evidence`

Relación entre claim y `transcriptSpanId`, con orden y rol principal/contextual.

### `claim_supersessions`

Relación entre claim nuevo y claim reemplazado.

### `draft_field_revisions`

- campo;
- valor anterior y nuevo;
- actor máquina/usuario;
- acción aceptar/rechazar/corregir;
- timestamp;
- claims asociados cuando corresponda.

La edición se protege por campo. Cambiar el modo no marca los cinco campos como editados.

Room habilitará exportación versionada de esquemas y tendrá pruebas de migración desde una base v5 poblada.

## Feedback y evaluación

### Evaluación sin corpus inicial

Los escenarios sintéticos existentes se convertirán en casos ejecutables. Cada caso tendrá:

- transcripción;
- claims esperados tipados;
- claims prohibidos tipados;
- ficha esperada;
- invariantes de evidencia;
- variantes metamórficas.

Se medirán:

- precisión, recall y F1 por categoría;
- exactitud de estado;
- precisión de evidencia;
- exactitud de supersesiones;
- exact match de campos;
- tasa de respuesta inválida;
- fallback y cache hit por proveedor.

No se publicarán porcentajes agregados engañosos cuando haya pocos casos. Los reportes incluirán conteos y fallos concretos.

### Correcciones del usuario

En Por confirmar se podrá:

- aceptar;
- rechazar;
- corregir.

Al guardar o aprobar se conserva el delta entre ficha automática y ficha final. `ClaimOrigin.USER_EDIT` y `MANUAL_MARKER` pasan a tener uso real.

### Corpus local opcional

La limpieza privada continúa siendo el comportamiento predeterminado. Antes de eliminar temporales, el usuario podrá elegir “Guardar ejemplo local para mejorar resultados”.

El ejemplo contendrá solamente:

- spans citados y una ventana contextual mínima;
- salida automática;
- decisión o corrección final;
- versiones del pipeline;
- procedencia y resultados sanitizados.

No contendrá audio, rutas, fecha pedagógica, ids de sesión ni claves. El usuario podrá revisar el texto, borrar todos los ejemplos y exportar/importar JSONL mediante el selector de archivos de Android.

El corpus servirá inicialmente para regresión, comparación de proveedores y ajuste de prompt. No se entrenará un modelo en el dispositivo durante esta fase.

## Interfaz

Durante interpretación se mostrará:

- paquete actual y total;
- proveedor o fallback actual;
- estado de caché;
- tiempo transcurrido;
- procedencia final `GEMINI`, `GROQ`, `OPENROUTER`, `MIXTO` o `LOCAL`;
- acción Reintentar IA;
- acción Continuar local.

La notificación del sistema distinguirá transcripción de interpretación.

La UI no mostrará cuotas estimadas. Solo mostrará información real entregada por el proveedor.

## Privacidad y costo

Se mantienen como invariantes:

- audio siempre local;
- remoto desactivado por defecto;
- consentimiento independiente;
- catálogo cerrado;
- ningún cambio automático a modelos pagos;
- credenciales en Android Keystore;
- ninguna red o clave real en CI;
- cuerpos privados ausentes de logs y trazas;
- funcionamiento completo sin proveedor remoto.

OpenRouter seguirá bloqueado a `openrouter/free` y requerirá un preflight de la clave para detectar capacidad de gasto no limitada cuando la API la informe.

## Estrategia de entrega

### `0.5.2-integrity`

Incluye:

- documentación y ramas corregidas;
- orden temporal;
- estado→campo;
- identidad global;
- evidencia persistente;
- reproyección local de modos;
- estado semántico separado;
- deadlines y cancelación;
- pruebas de migración y dispositivo.

### `0.6.0-quality-loop`

Incluye:

- extracción híbrida;
- quality gate y confianza efectiva;
- reparación real;
- esquema nativo de Gemini;
- evaluación ejecutable;
- observabilidad;
- feedback estructurado;
- corpus local opcional;
- benchmark manual de proveedores.

## Criterios de aceptación

- Los spans nunca cambian de orden al reiniciarse el reloj de un segmento.
- `EXERCISE + ASSIGNED` aparece únicamente en Tarea.
- No existen colisiones de claims entre paquetes, proveedores o sesiones.
- La evidencia múltiple y las supersesiones sobreviven cierre y reapertura.
- Páginas y ejercicios se asocian por evidencia, bloque y cronología estable.
- Una respuesta vacía o con evidencia numéricamente contradictoria no se acepta como éxito.
- Cambiar de modo realiza cero llamadas HTTP.
- Un fallo semántico nunca degrada una transcripción correcta.
- La sesión abandona la red dentro del presupuesto total y conserva ficha local.
- Los resultados locales también tienen checkpoint.
- La UI nunca queda indefinidamente en PROCESSING.
- Las correcciones se registran por campo o claim.
- CI ejecuta evaluación sintética, tests de migración, tests de fallos y builds sin red.
- La prueba física valida sesión breve, sesión extensa, modo avión, caché, pausa, kill/restart, cuota, timeout y clave inválida.
- El APK no contiene secretos ni simuladores de fallo de producción.

## Exclusiones

No se incluyen:

- envío de audio;
- inferencia remota durante la grabación;
- proveedores pagos;
- cuarto proveedor;
- consulta simultánea a varios proveedores para votar;
- diarización;
- entrenamiento local;
- servidor propio;
- sincronización del corpus;
- cambios automáticos de modelo o proveedor por benchmarking.

## Decisión de colaboración

Un agente integrador controla la rama `feature/phase5-2-quality`. Cada tarea usa una rama corta y un PR contra esa integración. Los contratos se congelan antes de iniciar trabajos paralelos. Ningún par de agentes modifica simultáneamente los mismos archivos de integración, esquema Room o coordinador.

Cada PR debe incluir:

- base y head SHA;
- requisito cubierto;
- archivos modificados;
- pruebas ejecutadas;
- métricas afectadas;
- riesgos;
- confirmación de ausencia de secretos y red real;
- dependencia habilitada para la siguiente ola.

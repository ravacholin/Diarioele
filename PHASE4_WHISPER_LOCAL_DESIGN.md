# Diseño aprobado de fase 4: Whisper local en español

## Resultado de la fase

La fase 4 reemplaza el reconocedor de voz dependiente del sistema Android por un motor propio basado en `whisper.cpp`. La aplicación transcribe en el teléfono, únicamente en español, sin permiso de Internet y sin depender de paquetes de idioma de Motorola o Google.

La transcripción de una jornada larga se divide en unidades recuperables. La interfaz informa el avance y permite pausar y retomar. Una interrupción no elimina el audio ni obliga a repetir los tramos ya confirmados.

## Reglas vinculantes

- Android únicamente, uso personal, sin cuenta, nube, backend ni API paga.
- Todo el procesamiento de audio ocurre localmente.
- La aplicación no solicita permiso de Internet.
- Se usa el modelo oficial Whisper `base` compatible con `whisper.cpp`.
- El modelo queda incluido dentro del APK y disponible desde la primera ejecución.
- La aplicación fija el idioma en español (`es`) y la tarea en transcripción.
- No existe detección automática, traducción ni selector de idiomas.
- El contexto inicial prioriza vocabulario rioplatense y docente sin agregar palabras que no estén respaldadas por el audio.
- El audio original sigue siendo temporal y nunca se borra por finalizar, transcribir, pausar, cancelar o fallar.
- El audio, los tramos transcritos, la evidencia y el borrador solo se borran después de la aprobación explícita y la verificación de la ficha permanente, según las reglas de fase 3.
- Los modos Conservador, Equilibrado y Exhaustivo modifican la proyección de evidencia a la ficha, no el idioma ni la fidelidad del motor de transcripción.
- Los cinco campos permanentes no cambian: temas, actividades realizadas, páginas, ejercicios hechos y tarea.
- La fase conserva el estilo minimalista brutalista moderno, elegante, oscuro y monocromo.
- Los documentos del proyecto permanecen como Markdown en la raíz; no se crea `docs`.

## Modelo y procedencia

El APK contiene los pesos oficiales multilingües `base`, porque OpenAI no publica una variante oficial exclusiva para español. La capacidad interna para otros idiomas no se expone ni se usa: el motor recibe siempre `language = "es"`, desactiva la detección de idioma y usa la tarea `transcribe`, nunca `translate`.

El modelo se obtiene durante la compilación desde la distribución oficial de `whisper.cpp`, mediante una revisión inmutable. La compilación verifica su SHA-256 antes de empaquetarlo y falla si el archivo no coincide. El APK no descarga pesos durante la instalación ni durante el uso.

El contexto inicial será breve y estable. Incluirá formas y términos frecuentes de una clase de ELE, por ejemplo: `vos`, `ustedes`, `página`, `ejercicio`, `tarea`, `pretérito`, `subjuntivo`, `actividad`, `lectura`, `audición` y `para la próxima clase`. Este contexto orienta la decodificación, pero no autoriza al extractor a inventar contenido.

## Integración nativa

Se incorpora una copia mínima del código oficial de `whisper.cpp`, fijada a un commit identificado, junto con su licencia. Android la compila mediante CMake y NDK para `arm64-v8a`, arquitectura del teléfono objetivo. No se usa una biblioteca comunitaria precompilada.

La frontera entre Kotlin y C++ queda reducida a un adaptador JNI con responsabilidades claras:

- cargar y validar el modelo una sola vez por proceso;
- recibir muestras PCM mono de 16 kHz;
- transcribir un intervalo con idioma español y contexto fijo;
- devolver texto, marcas temporales y métricas disponibles;
- liberar memoria ante finalización, cancelación o error.

`WhisperTranscriptionEngine` implementa la interfaz existente `TranscriptionEngine`. El motor anterior basado en `SpeechRecognizer` deja de ser el motor activo y se elimina de la experiencia de usuario toda referencia a descargar o comprobar el modelo español de Android.

## División y continuidad

Los WAV actuales ya usan PCM mono de 16 bits a 16 kHz. La app valida el encabezado y lee rangos directamente desde el archivo; no crea copias completas del audio.

Cada segmento grabado se procesa en ventanas de 30 segundos, con 2 segundos de solapamiento para no cortar palabras en los límites. Los resultados solapados se deduplican mediante marcas temporales antes de persistirse. El avance confirmado excluye los 2 segundos que se reutilizarán en la ventana siguiente. Cada ventana confirmada produce un checkpoint transaccional que contiene:

- sesión y segmento;
- posición de audio confirmada;
- cantidad total y cantidad procesada;
- spans aceptados con tiempos absolutos;
- estado: pendiente, procesando, pausado, completo o fallido;
- código técnico del último error, cuando exista.

El checkpoint se escribe antes de avanzar a la ventana siguiente. Al retomar, el motor consulta el último límite confirmado y no vuelve a procesar el audio anterior.

## Ejecución persistente

El procesamiento se inicia únicamente por una acción explícita del usuario. Un `CoroutineWorker` único de WorkManager por sesión ejecuta las ventanas en orden y pasa a primer plano con una notificación visible mientras está activo.

La pantalla y la notificación muestran información comprobable:

- porcentaje calculado sobre milisegundos confirmados;
- bloque y tramo actuales;
- tiempo de audio procesado y total;
- estado procesando, pausado, completado o fallido.

No se muestra una estimación de tiempo restante hasta contar con mediciones suficientes en el teléfono real. Cerrar la pantalla no cancela el trabajo. Reiniciar el proceso de la aplicación permite reconstruir el estado desde Room.

Pausar solicita una detención segura entre ventanas. Retomar crea nuevamente el trabajo único de la sesión desde el checkpoint. Cancelar la ejecución equivale a pausarla respecto de la conservación de datos: nunca borra audio, transcripción parcial ni ficha.

## Flujo de datos

1. El usuario finaliza la jornada; los WAV quedan cerrados y verificados.
2. El usuario toca `PROCESAR AUDIO`.
3. La app crea o recupera el checkpoint de la sesión y arranca el trabajo persistente.
4. El motor carga `base`, fuerza español y procesa ventanas en orden.
5. Cada ventana confirmada se guarda en Room y actualiza el avance.
6. Cuando todos los segmentos terminan, la aplicación ejecuta el extractor literal, el reductor y el proyector ya existentes.
7. La ficha editable aparece con los cinco campos y el modo elegido.
8. La aprobación y limpieza siguen el protocolo seguro de fase 3: guardar, releer, comparar y recién entonces borrar temporales.

## Fallos y recuperación

La interfaz debe traducir cada fallo a una explicación concreta y una acción posible. Como mínimo distingue:

- modelo ausente o con huella inválida;
- biblioteca nativa incompatible o no cargable;
- WAV ausente, incompleto o con formato inválido;
- memoria insuficiente;
- error interno de Whisper;
- proceso interrumpido por Android;
- pausa o cancelación solicitada por el usuario.

Ninguno de estos fallos modifica la ficha permanente ni inicia limpieza. Un fallo de una ventana conserva todos los checkpoints anteriores y permite reintentar desde el mismo punto. Un modelo ausente o inválido no ofrece una descarga dentro de la app: indica que la instalación está dañada y requiere instalar nuevamente un APK válido.

## Interfaz

Se elimina la sección `DESCARGAR ESPAÑOL LOCAL`, `COMPROBAR ESPAÑOL LOCAL` y sus estados asociados. La sección de transcripción pasa a mostrar:

- `WHISPER LOCAL · ESPAÑOL`;
- modelo integrado y disponible;
- avance numérico y barra de progreso;
- botón principal para procesar o retomar;
- botón secundario para pausar mientras el trabajo está activo;
- mensaje de error específico y botón de reintento cuando corresponde.

La pantalla nunca presenta un giro indefinido sin estado. Si una ventana de 30 segundos no termina ni informa cancelación dentro de 5 minutos, el trabajo registra un fallo recuperable por tiempo agotado y conserva todo.

## Cambios de persistencia

Room incorpora una tabla de checkpoints de transcripción vinculada a sesión y segmento. La migración conserva todas las sesiones, diarios, audios y transcripciones existentes. Los checkpoints forman parte de los temporales que se eliminan después de aprobar y verificar el diario.

El estado visible se deriva de Room y del trabajo persistente, no de variables exclusivas de la pantalla. Esto permite recuperar correctamente la interfaz después de rotación, cierre o reinicio del proceso.

## Pruebas

La implementación mantiene todas las pruebas existentes y agrega:

- pruebas JVM para cálculo de ventanas, solapamiento y deduplicación;
- pruebas JVM para creación, avance, pausa, reanudación y recuperación de checkpoints;
- pruebas de migración de Room sin pérdida de datos;
- pruebas del coordinador para evitar reprocesar ventanas confirmadas;
- pruebas del adaptador Kotlin ante códigos de error nativos;
- prueba instrumental que carga el modelo y procesa PCM válido sin bloquear la interfaz;
- prueba instrumental de interrupción y recuperación del trabajo persistente;
- recorrido completo que verifica que ningún fallo de Whisper borra temporales;
- regresión completa de edición, aprobación, archivo, portapapeles y limpieza de fase 3.

## Criterios de aceptación en teléfono real

La primera prueba usa una grabación nueva de unos 10 segundos con tema, actividad, página, ejercicio y tarea. Debe mostrar avance, finalizar o producir un error específico y nunca quedar indefinidamente sin información.

Después se prueban duraciones crecientes antes de una jornada real: 1 minuto, 10 minutos y un bloque prolongado. En cada prueba se verifica pausa, cierre de la app, reapertura y continuación desde un checkpoint. La velocidad final se mide en el Moto g max con Android 16; no se promete transcripción en tiempo real antes de esa medición.

El release gate exige:

- compilación reproducible del código nativo y verificación SHA-256 del modelo;
- pruebas unitarias, instrumentales y lint aprobados;
- APK que funciona sin conexión y sin permiso de Internet;
- prueba física corta completada o fallida con diagnóstico específico;
- audio reproducible y reintento disponible después de cualquier fallo;
- borrado únicamente después de aprobar y verificar la ficha.

## Fuera de alcance

- entrenamiento o ajuste fino de un modelo argentino propio;
- identificación de hablantes;
- transcripción en tiempo real durante la clase;
- sincronización o respaldo en nube;
- conservación permanente del audio o de la transcripción completa;
- incorporación de otros idiomas;
- firma estable del APK, que permanece como el siguiente trabajo independiente antes de usar la aplicación con datos irremplazables.

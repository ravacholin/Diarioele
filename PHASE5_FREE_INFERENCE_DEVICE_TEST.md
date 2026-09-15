# Prueba física — Fase 5 (router de inferencia gratuito)

APK: `0.5.0-free-router` (versionCode 7). Dispositivo de aceptación: Moto g max.

Una compilación verde no prueba el comportamiento real en el teléfono. Esta prueba la
completa el usuario, gradualmente. Conservar el audio ante cualquier fallo; no aprobar una
ficha durante una prueba de fallo (aprobar elimina los temporales).

## Preparación

1. Instalar el APK `0.5.0-free-router`. Si la firma debug impide actualizar sobre una
   versión previa, desinstalar la anterior (borra sus datos locales).
2. Confirmar que el APK **no** cambió su naturaleza offline por defecto: sin proveedores
   configurados, la interpretación es 100% local.
3. En **CONFIGURACIÓN → INTERPRETACIÓN CON IA**: para Gemini, dar consentimiento, pegar una
   clave de un proyecto **sin facturación**, guardar, activar y usar **PROBAR CONEXIÓN**.
   Verificar que solo se muestran los últimos cuatro caracteres de la clave.

## Casos testeables con esta versión

1. **Gemini exitoso.** Con Gemini activo y clave válida, grabar 10 s mencionando los cinco
   campos, procesar y verificar que la ficha se arma con datos correctos. La ficha indica el
   origen de los claims.
2. **Fallback local (modo avión).** Activar modo avión y procesar otra grabación: la ficha
   se arma igual con el extractor local, sin errores ni pérdida de audio.
3. **Cambio de modo sin red.** Cambiar CONSERVADOR/EQUILIBRADO/EXHAUSTIVO sobre una ficha ya
   procesada: cambia lo mostrado sin volver a llamar a la red (verificable con modo avión).
4. **Cierre y reapertura con caché.** Cerrar y reabrir la app tras procesar: la ficha se
   reconstruye desde la caché sin re-consultar al proveedor.
5. **Edición, aprobación y limpieza.** Editar campos, aprobar con `APROBAR Y BORRAR AUDIO` y
   confirmar que el audio y los temporales (incluida la caché) se borran; la ficha
   permanente queda intacta.
6. **Clave inválida sin exposición.** Guardar una clave inválida y usar PROBAR CONEXIÓN:
   debe informar que la clave no es válida sin mostrarla; un procesamiento cae al fallback
   local sin filtrar la clave.
7. **Groq / OpenRouter.** Configurar cada uno igual que Gemini y repetir el caso 1 para
   confirmar que responden como proveedores.

## Pendiente de una versión debug con inyección de fallos

Los casos de **encadenamiento simulado** (Gemini 429 → Groq; Gemini + Groq 429 →
OpenRouter; todos fallan → local con ficha mixta) requieren un hook de fallos simulados
disponible solo en debug, que **todavía no está implementado**. Con el encadenamiento real
solo se puede forzar el fallback quitando la red (caso 2) o con una clave inválida (caso 6).
Este hook y un panel de estado de ejecución con la procedencia `GEMINI/GROQ/OPENROUTER/
MIXTO/LOCAL` y los botones `REINTENTAR INFERENCIA` / `CONTINUAR CON FICHA LOCAL` quedan como
mejora acotada para una próxima iteración.

## Registro de la prueba

Anotar por cada caso: resultado, origen de la ficha y cualquier error. Cada fallo semántico
observado se convierte en el escenario sintético mínimo que lo reproduzca (sin audio ni
corpus). No se declara la Fase 5 completa hasta registrar aquí la prueba física satisfactoria
con al menos éxito primario (Gemini) y fallback local en el Moto g max.

# Prueba física del APK Whisper (Fase 4)

Última actualización: 2026-09-14

Este documento describe cómo validar en el teléfono la transcripción local con
Whisper. Una compilación verde en GitHub Actions **no** prueba el rendimiento real
ni la calidad de la transcripción. La validación final debe hacerse gradualmente en
el dispositivo de aceptación.

## Dispositivo de aceptación

- Moto g max con Android 16.
- Instalar el APK `DiarioClase-Android-debug` publicado por GitHub Actions
  (`versionName = 0.4.0-whisper`, `versionCode = 6`).

## Preparación

1. Instalar el APK.
2. **Desactivar Wi-Fi y datos móviles.** La app no pide permiso de Internet; la
   transcripción debe funcionar completamente offline.
3. Conceder los permisos de micrófono y notificaciones cuando la app los solicite.
4. Si una firma debug impide actualizar sobre una versión previa, desinstalar la
   anterior sabiendo que eso elimina sus datos locales.

## Verificaciones del artefacto (antes de instalar)

Sobre el APK de la CI:

```bash
unzip -l app-debug.apk | rg 'ggml-base.bin|lib/arm64-v8a/libdiarioclase_whisper.so'
apkanalyzer manifest permissions app-debug.apk | rg 'android.permission.INTERNET'
```

Esperado:

- El modelo `ggml-base.bin` y la biblioteca `libdiarioclase_whisper.so` (arm64-v8a)
  aparecen dentro del APK.
- La búsqueda del permiso `INTERNET` termina **sin resultados**.

## Secuencia de prueba gradual

Avanzar de menor a mayor duración. No pasar al siguiente paso hasta que el anterior
funcione.

1. **10 segundos.** Grabar mencionando claramente los cinco campos (tema,
   actividad, página, ejercicio y tarea). Finalizar el día y procesar. Registrar
   duración del procesamiento, progreso mostrado, texto obtenido y la ficha
   generada.
2. **1 minuto — pausa y reanudación.** Durante el procesamiento, tocar
   `PAUSAR PROCESAMIENTO`, confirmar que aparece `RETOMAR` y que el porcentaje
   confirmado no retrocede. Reanudar y verificar que continúa desde el mismo punto.
3. **10 minutos — cierre y reapertura.** Iniciar el procesamiento, cerrar la app o
   apagar la pantalla, reabrir y confirmar que el progreso persistido se recupera y
   no reinicia desde cero.
4. **Bloque largo.** Solo después de superar los pasos anteriores, probar un bloque
   de duración habitual (90, 60 o 50 minutos).

## Reglas de seguridad durante la prueba

- El audio se conserva durante grabación, transcripción, errores y revisión.
- **No aprobar una ficha durante una prueba de fallo**, porque aprobar elimina los
  temporales (audio, transcripción, evidencia, checkpoints y run).
- Si una prueba física falla, conservar el audio y registrar estado, progreso y
  error antes de cambiar el diseño.

## Registro de resultados

Anotar, para cada paso: duración de la grabación, tiempo de procesamiento,
porcentaje confirmado final, texto transcrito, ficha resultante y cualquier error.
Distinguir siempre entre validado por GitHub Actions y probado físicamente: ninguno
implica el otro.

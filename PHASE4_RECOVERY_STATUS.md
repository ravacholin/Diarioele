# Recuperación de la Fase 4

Última actualización: 2026-09-14

## Objetivo

Incorporar transcripción local, privada y gratuita en español rioplatense. El audio se conserva mientras se procesa y revisa, y se elimina solamente después de la confirmación explícita del usuario.

## Fuente de verdad

- Repositorio: `ravacholin/Diarioele`
- Rama de trabajo: `feature/phase4-whisper-recovery`
- PR borrador: [#1](https://github.com/ravacholin/Diarioele/pull/1)
- Base inicial: `main@35c45a1`
- Último checkpoint funcional validado: `a53b321` (Tasks 7, 8 y 9)
- Última validación completa: GitHub Actions `#74`, exitosa

No debe quedar trabajo terminado únicamente en un entorno temporal. Cada bloque funcional se confirma en esta rama y se valida con GitHub Actions antes de continuar.

## Continuidad entre agentes

- Punto de entrada operativo: [`AGENTS.md`](AGENTS.md).
- Estado técnico, arquitectura, riesgos y próximo paso: [`PHASE4_HANDOFF.md`](PHASE4_HANDOFF.md).
- Todo agente debe actualizar ambos registros después de subir y validar un bloque nuevo.

## Estado

| Bloque | Estado | Evidencia remota |
|---|---|---|
| Infraestructura durable y CI de la rama | Recuperado | PR #1, workflow #31 |
| Modelo español local | Recuperado y validado | `ec499a2`, workflow #29 |
| Ventanas WAV y deduplicación temporal | Recuperado y validado | `3e6291e`, workflow #33 |
| Checkpoints Room y migración 3→4 | Recuperado y validado | `5b24221`, workflow #37 |
| Motor nativo whisper.cpp ARM64 | Recuperado y validado | `47ab1b9`, workflow #41 |
| Coordinador reanudable por ventana | Recuperado y validado | `2872f49`, workflow #49 |
| Ejecución persistente en segundo plano | Recuperado y validado | `b5c42b3`, workflow #57 |
| UI observable sin descarga de idioma (Task 7) | Recuperado y validado | `5b60dd0`, workflow #67 |
| Limpieza segura de runs y checkpoints (Task 8) | Recuperado y validado | `2824a22`, workflow #70 |
| Composición y entrega del APK (Task 9) | Recuperado y validado | `a53b321`, workflow #74 |
| Prueba física en el Moto g max | Pendiente | |

## Garantías ya implementadas

- Una pausa solicitada queda persistida.
- Cada archivo mantiene su último milisegundo confirmado y su cantidad de ventanas procesadas.
- Una ventana confirmada no vuelve a transcribirse después de reiniciar el proceso.
- Texto, checkpoint, progreso y estado del archivo se guardan en una única transacción.
- Si una ventana falla, se conservan el texto y el checkpoint anteriores.
- El solapamiento no duplica frases y tampoco elimina repeticiones legítimas posteriores.
- Los campos editados manualmente sobreviven a cambios del modo de interpretación.
- El motor nativo se carga recién al iniciar la transcripción, no al abrir la aplicación.
- Migrar una instalación existente conserva sesiones y borradores previos.
- Los fallos y cierres no borran audio ni resultados confirmados.
- El modelo se instala en almacenamiento privado no respaldable y se valida por tamaño y SHA-256.
- Whisper queda fijado a español, sin traducción ni detección automática.
- El APK no solicita permiso de Internet y transcribe localmente.
- La biblioteca nativa se compila solo para `arm64-v8a`.
- La licencia MIT de whisper.cpp se incluye dentro del APK.
- Cada día tiene un único trabajo persistente de transcripción administrado por WorkManager.
- Iniciar o reanudar limpia la pausa antes de encolar el trabajo; pausar guarda primero la pausa y después cancela el trabajo.
- La transcripción continúa en primer plano aunque se apague la pantalla o se cierre la interfaz.
- La notificación informa el progreso y permite pausar el procesamiento.
- Cada ventana tiene un límite de cinco minutos y un fallo conserva el audio, el texto y el checkpoint anteriores.
- Cancelar el trabajo alcanza también a la inferencia nativa de Whisper.
- El planificador se inicializa solamente cuando se usa, para no interferir con el arranque ni con las pruebas.

## Fuente nativa reproducible

Durante la compilación, CMake obtiene `whisper.cpp` desde su repositorio oficial y lo fija al commit `927cfce34f31707e17f2bff35c349632fb9e2c3a`, correspondiente a v1.9.4. Esto evita guardar miles de archivos generados en este repositorio. La red se usa al construir el APK, nunca durante la transcripción en el teléfono.

## Reglas de continuidad

1. Un bloque no cuenta como recuperado hasta que su commit exista en GitHub.
2. Este archivo se actualiza después de cada validación.
3. GitHub Actions debe compilar y ejecutar las pruebas en cada push.
4. El PR permanece en borrador hasta superar todas las validaciones.
5. Si un entorno desaparece, el siguiente retoma desde el SHA remoto indicado aquí.
6. El APK se publica como artefacto de GitHub Actions; el modelo y los audios no se versionan.
7. Los audios temporales permanecen privados en el teléfono y solo se borran con confirmación del usuario.

## Estado de integración

La Fase 4 está completa en código y validada por GitHub Actions `#74` (`a53b321`): la interfaz delega en `TranscriptionWorkScheduler` y observa el progreso persistido desde Room (Task 7); la limpieza temporal borra y cuenta `transcription_runs` y `transcription_checkpoints`, archivando solo con cero temporales (Task 8); y el motor de reconocimiento anterior fue eliminado, con versión `0.4.0-whisper` y el APK offline sin permiso de Internet (Task 9). Lo único pendiente es la **prueba física en el Moto g max** (`PHASE4_WHISPER_DEVICE_TEST.md`); ninguna CI verde la reemplaza y el audio no se borra sin aprobación explícita del usuario.

## Trabajo local perdido

La implementación local anterior llegó hasta el bloque 8, con último SHA local conocido `ab32055`, pero esos objetos no fueron enviados al remoto y el entorno quedó inaccesible. Por eso se reconstruye en esta rama sin depender de que dicho entorno vuelva a abrirse.

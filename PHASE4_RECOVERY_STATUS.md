# Recuperación de la Fase 4

Última actualización: 2026-09-14

## Objetivo

Incorporar transcripción local, privada y gratuita en español rioplatense. El audio se conserva mientras se procesa y revisa, y se elimina solamente después de la confirmación explícita del usuario.

## Fuente de verdad

- Repositorio: `ravacholin/Diarioele`
- Rama de trabajo: `feature/phase4-whisper-recovery`
- PR borrador: [#1](https://github.com/ravacholin/Diarioele/pull/1)
- Base inicial: `main@35c45a1`
- Último checkpoint funcional validado: `5b24221`
- Última validación completa: GitHub Actions `#37`, exitosa

No debe quedar trabajo terminado únicamente en un entorno temporal. Cada bloque funcional se confirma en esta rama y se valida con GitHub Actions antes de continuar.

## Estado

| Bloque | Estado | Evidencia remota |
|---|---|---|
| Infraestructura durable y CI de la rama | Recuperado | PR #1, workflow #31 |
| Modelo español local | Recuperado y validado | `ec499a2`, workflow #29 |
| Ventanas WAV y deduplicación temporal | Recuperado y validado | `3e6291e`, workflow #33 |
| Checkpoints Room y migración 3→4 | Recuperado y validado | `5b24221`, workflow #37 |
| Motor nativo whisper.cpp | Pendiente de reconstrucción | |
| Coordinador reanudable por ventana | Pendiente de reconstrucción | |
| Extracción conservadora de la ficha | Pendiente de reconstrucción | |
| Pantallas de progreso, error y revisión | Pendiente de reconstrucción | |
| Integración completa y prueba en teléfono | Pendiente | |

## Garantías ya implementadas

- Una pausa solicitada queda persistida.
- Cada archivo mantiene su último milisegundo confirmado y su cantidad de ventanas procesadas.
- Repetir el guardado de un checkpoint lo avanza sin duplicarlo.
- Eliminar un audio elimina su checkpoint huérfano por cascada.
- Migrar una instalación existente conserva sesiones y borradores previos.
- Los fallos y cierres no borran audio ni resultados confirmados.

## Reglas de continuidad

1. Un bloque no cuenta como recuperado hasta que su commit exista en GitHub.
2. Este archivo se actualiza después de cada validación.
3. GitHub Actions debe compilar y ejecutar las pruebas en cada push.
4. El PR permanece en borrador hasta superar todas las validaciones.
5. Si un entorno desaparece, el siguiente retoma desde el SHA remoto indicado aquí.
6. El APK se publica como artefacto de GitHub Actions; el modelo y los audios no se versionan.
7. Los audios temporales permanecen privados en el teléfono y solo se borran con confirmación del usuario.

## Trabajo local perdido

La implementación local anterior llegó hasta el bloque 8, con último SHA local conocido `ab32055`, pero esos objetos no fueron enviados al remoto y el entorno quedó inaccesible. Por eso se reconstruye en esta rama sin depender de que dicho entorno vuelva a abrirse.

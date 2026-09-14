# Recuperación de la Fase 4

Última actualización: 2026-09-14

## Objetivo

Incorporar transcripción local, privada y gratuita en español rioplatense. El audio se conserva mientras se procesa y revisa, y se elimina solamente después de la confirmación explícita del usuario.

## Fuente de verdad

- Repositorio: `ravacholin/Diarioele`
- Rama de trabajo: `feature/phase4-whisper-recovery`
- PR borrador: [#1](https://github.com/ravacholin/Diarioele/pull/1)
- Base inicial: `main@35c45a1`
- Último checkpoint funcional: `ec499a2`
- Validación: GitHub Actions `#29`

No debe quedar trabajo terminado únicamente en un entorno temporal. Cada bloque funcional se confirma en esta rama y se valida con GitHub Actions antes de continuar.

## Estado

| Bloque | Estado | Evidencia remota |
|---|---|---|
| Infraestructura durable y CI de la rama | Recuperado | PR #1, workflow #29 |
| Modelo español local | Reconstruido, validación en curso | `ec499a2` |
| Motor nativo whisper.cpp | Pendiente de reconstrucción | |
| Conversión de audio y segmentación | Pendiente de reconstrucción | |
| Procesamiento persistente y reanudable | Pendiente de reconstrucción | |
| Extracción conservadora de la ficha | Pendiente de reconstrucción | |
| Pantallas de progreso, error y revisión | Pendiente de reconstrucción | |
| Pruebas y APK verificable | En curso | workflow #29 |

## Reglas de continuidad

1. Un bloque no cuenta como recuperado hasta que su commit exista en GitHub.
2. Este archivo se actualiza en el mismo checkpoint o inmediatamente después.
3. GitHub Actions debe compilar y ejecutar las pruebas en cada push.
4. El PR permanece en borrador hasta superar todas las validaciones.
5. Si un entorno desaparece, el siguiente retoma desde el SHA remoto indicado aquí.
6. El APK se publica como artefacto de GitHub Actions; el modelo y los audios no se versionan.
7. Los audios temporales permanecen privados en el teléfono y solo se borran con confirmación del usuario.

## Trabajo local perdido

La implementación local anterior llegó hasta el bloque 8, con último SHA local conocido `ab32055`, pero esos objetos no fueron enviados al remoto y el entorno quedó inaccesible. Por eso se reconstruye en esta rama sin depender de que dicho entorno vuelva a abrirse.

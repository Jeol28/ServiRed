# ADR-07 · El estado del reporte se actualiza con eventos de caso

**Estado:** aceptada · **Fecha:** 2026-11-14

## Contexto
RF-2 exige que el ciudadano consulte el estado y la historia de su reporte, pero
el estado operativo lo cambia Atención. Reportes y Atención no deben llamarse
directamente (ADR-03).

## Decisión
- Reportes consume `caso.actualizado` y `caso.cerrado` (cola
  `reportes.eventos-casos`) y actualiza, vía Persistencia, el estado visible del
  reporte y su historia.
- Estados del **reporte** (vista ciudadana): `RECIBIDO → EN_ATENCION → RESTABLECIDO → CERRADO`.
- Estados del **caso** (vista operador): `RECIBIDO → EN_ATENCION → RESUELTO → CERRADO`.
- `RESTABLECIDO` lo produce el restablecimiento (RF-3); `CERRADO`, el cierre del caso.

## Implementación (fase 5)
| Evento | Estado del reporte | Aplica desde |
|---|---|---|
| `caso.actualizado` con estado `EN_ATENCION` | `EN_ATENCION` | `RECIBIDO` |
| `caso.actualizado` con estado `RESUELTO` | `RESTABLECIDO` | `RECIBIDO`, `EN_ATENCION` |
| `caso.actualizado` con estado `CERRADO` o `caso.cerrado` | `CERRADO` | cualquier estado no cerrado |

- El estado **nunca retrocede**: un evento tardío o repetido no lo cambia.
- Idempotencia por `idEvento` en Persistencia (`aplicarEstadoDeCaso`).
- Si el operador resolvió primero, el reporte queda `RESTABLECIDO` sin fecha y el
  ciudadano aún puede registrar la hora real (se calcula la duración).

## Consecuencias
- Consistencia eventual: el estado del ciudadano puede tardar segundos en reflejar
  la acción del operador.
- Si Atención cae, el reporte sigue registrándose y consultándose (RNF-DC2).

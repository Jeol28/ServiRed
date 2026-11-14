# ADR-06 · Escritura síncrona en Persistencia y outbox transaccional

**Estado:** aceptada · **Fecha:** 2026-11-14 · **Corrige:** vista de procesos (flujos 1 y 2) del SAD

## Contexto
La vista de procesos del SAD muestra a Persistencia **consumiendo** `reporte.creado`
para insertar el reporte, y a Reportes publicando "una vez validada" la solicitud.
Eso contradice:
- ADR-04 (flujo síncrono Canal → Gateway → Reportes → Persistencia → Supabase),
- el C4 nivel 2 ("publica eventos después de confirmar la persistencia"),
- RNF-E2 (no perder reportes aceptados): si el reporte solo existe en una cola,
  se confirma al ciudadano algo que aún no está guardado.

Además, publicar después de un 2xx de Persistencia deja una ventana en la que
el proceso puede caer entre la escritura y la publicación, y el evento se pierde.

## Decisión
1. Persistencia **no consume eventos de negocio**. Toda escritura es una llamada
   síncrona a una operación de negocio (`guardarReporte`, `actualizarEstadoCaso`…).
2. **Outbox transaccional en Persistencia** (mencionado en el C4 nivel 2):
   el servicio dueño construye el evento y lo envía junto con la operación;
   Persistencia guarda el dato y el evento (`infraestructura.outbox`) en la
   **misma transacción**. Un relay publica los pendientes en RabbitMQ con
   confirmación del broker y marca `publicado_en`.
3. El evento del SAD `reportes.resueltos` se reemplaza por `caso.actualizado`
   y `caso.cerrado`, publicados por Atención.
4. La idempotencia usa `(X-Correlation-Id, operación)` como clave
   (`infraestructura.solicitudes_procesadas`).

## Consecuencias
- Un evento existe si y solo si su dato se persistió; entrega al-menos-una-vez,
  por eso los consumidores son idempotentes por `idEvento`.
- Reportes sigue siendo el dueño semántico de sus eventos; Persistencia solo
  los almacena y publica sin interpretar `datos`.
- Persistencia gana una responsabilidad de infraestructura (relay); se monitorea
  el tamaño del outbox pendiente (fase 7).

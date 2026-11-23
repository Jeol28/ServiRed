# ADR-08 · Validación comunitaria, duración, enrutamiento y municipio

**Estado:** aceptada · **Fecha:** 2026-11-14

## Contexto
El SAD no fija umbrales ni reglas concretas para RF-3, RF-4 y RF-6.

## Decisión
- **Validación comunitaria (RF-4):** un corte o restablecimiento queda validado
  con **3 confirmaciones netas** (confirmaciones − controversias) de ciudadanos
  distintos ubicados a **≤ 1 km**. El autor no valida su propio reporte; un voto
  por usuario. Umbral y radio son configurables.
- **Implementación de la validación (fase 4):** el voto de un ciudadano sobre un
  objeto es definitivo (repetirlo es idempotente y no lo cambia). Validar **no**
  cambia el estado del reporte, para evitar cierres prematuros: el cierre lo
  decide el operador. Reportes calcula las confirmaciones netas y Persistencia
  guarda el voto con **concurrencia optimista** (`version_validaciones`): si otro
  voto entró en medio, Reportes vuelve a leer y recalcular (hasta 3 veces), así
  el evento `validacion.registrada` siempre lleva el total real. La ubicación del
  validador solo se usa para medir la distancia (haversine) y no se guarda.
- **Restablecimiento (fase 4):** lo registra el autor del reporte, una sola vez,
  desde `RECIBIDO` o `EN_ATENCION`; los vecinos lo confirman o controvierten.
- **Duración (RF-3):** `fechaRestablecimiento − inicioEstimado`. El inicio no
  puede ser futuro y el restablecimiento debe ser posterior (RNF-DAT1).
- **Enrutamiento (RF-6):** cada empresa operadora declara su cobertura
  (municipio × tipo de servicio). Desde [ADR-35](ADR-35-empresas-reales-y-zonas-de-servicio.md), empresas reales con una sola dueña por zona. Un operador solo ve los casos de su empresa;
  el regulador ve todo en solo lectura y exporta.
- **Inicio del corte (RNF-DAT1, fase 2):** no puede ser futuro (se toleran
  **5 minutos** de diferencia de reloj del dispositivo y, en ese caso, se ajusta
  al instante de registro) ni tener más de **30 días**. Las fechas viajan con
  zona horaria (ISO 8601) y se guardan en UTC.
- **Municipio:** el cliente envía latitud y longitud; Persistencia resuelve el
  municipio por el **centroide DIVIPOLA más cercano** y se valida que el punto
  esté dentro del territorio colombiano.

## Consecuencias
- El centroide puede asignar mal puntos cercanos a un límite municipal; se acepta
  para el MVP y se puede reemplazar por polígonos (PostGIS) sin cambiar contratos.
  **Reemplazado por [ADR-27](ADR-27-municipio-por-poligonos-oficiales.md):** el municipio
  ahora es el polígono oficial del DANE que contiene el punto; el centroide queda solo como
  respaldo.

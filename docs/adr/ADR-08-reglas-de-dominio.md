# ADR-08 · Validación comunitaria, duración, enrutamiento y municipio

**Estado:** aceptada · **Fecha:** 2026-11-14

## Contexto
El SAD no fija umbrales ni reglas concretas para RF-3, RF-4 y RF-6.

## Decisión
- **Validación comunitaria (RF-4):** un corte o restablecimiento queda validado
  con **3 confirmaciones netas** (confirmaciones − controversias) de ciudadanos
  distintos ubicados a **≤ 1 km**. El autor no valida su propio reporte; un voto
  por usuario. Umbral y radio son configurables.
- **Duración (RF-3):** `fechaRestablecimiento − inicioEstimado`. El inicio no
  puede ser futuro y el restablecimiento debe ser posterior (RNF-DAT1).
- **Enrutamiento (RF-6):** cada empresa operadora declara su cobertura
  (municipio × tipo de servicio). Un operador solo ve los casos de su empresa;
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

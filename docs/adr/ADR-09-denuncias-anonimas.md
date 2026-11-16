# ADR-09 · Anonimato de las denuncias

**Estado:** aceptada · **Fecha:** 2026-11-14 · **Requisitos:** RF-5, RNF-SEG4, RNF-SEG5

## Decisión
- Endpoint público `POST /api/v1/denuncias` **sin JWT**; si llega `Authorization`,
  el gateway lo descarta antes de enrutar.
- Límite de uso con hash de IP **solo en memoria** del gateway; la IP no se
  escribe en ningún log (los logs de acceso nunca incluyen IP).
- Se eliminan los metadatos EXIF de las fotografías (GPS, modelo del equipo).
- El `X-Correlation-Id` de la denuncia es aleatorio; no se deriva del cliente.
- El evento `denuncia.recibida` no contiene identificadores del denunciante e
  Indicadores no lo consume (no hay riesgo de publicarlo como dato abierto).

## Implementación (fase 4)
- El gateway ignora el `X-Correlation-Id` del cliente en `/api/v1/denuncias/**`
  y genera uno nuevo; además descarta `Authorization` y `Cookie`.
- La tabla `reportes.denuncias` no tiene columnas de identidad (ni cuenta, ni IP,
  ni dispositivo); la prueba e2e lo verifica, igual que el evento y los logs.
- La foto se valida y sanea como en los reportes ([ADR-13](ADR-13-evidencias-y-almacenamiento.md)).
- Límite de 5 denuncias por minuto por cliente (hash salado de la IP, en memoria).

## Consecuencias
- No es posible contactar al denunciante; la moderación (Atención) trabaja solo
  con el contenido y la evidencia.

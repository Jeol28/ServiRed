# Contratos

Única fuente de verdad para integrar servicios y clientes. Ningún servicio comparte
código con otro: se integran solo a través de estos contratos (RNF-DAT3).

| Carpeta | Contenido |
|---|---|
| [`api-publica/servired-api.v1.yaml`](api-publica/servired-api.v1.yaml) | OpenAPI 3.1 del **gateway**. Es lo único que consumen los portales y las apps Android/iOS. |
| [`internos/persistencia.v1.yaml`](internos/persistencia.v1.yaml) | OpenAPI 3.1 de la API interna de persistencia (operaciones de negocio). |
| [`eventos/asyncapi.yaml`](eventos/asyncapi.yaml) | AsyncAPI 3.0: canales, productores y consumidores en RabbitMQ. |
| [`eventos/esquemas/`](eventos/esquemas/) | JSON Schema 2020-12: sobre común y `datos` de cada evento versionado. |

## Reglas de versionado
- HTTP: versión en la ruta (`/api/v1`, `/interno/v1`). Un cambio incompatible crea `v2`
  y la versión anterior se mantiene durante el período acordado.
- Eventos: routing key `<agregado>.<evento>.v<N>`; un cambio incompatible publica
  `v2` en paralelo hasta que todos los consumidores migren.
- Agregar campos opcionales es compatible; quitar o renombrar no lo es.

## Eventos mínimos
| Evento | Productor | Consumidores |
|---|---|---|
| `reporte.creado` | reportes | atencion, indicadores, auditoria |
| `restablecimiento.registrado` | reportes | atencion, indicadores, auditoria |
| `validacion.registrada` | reportes | atencion, indicadores, auditoria |
| `denuncia.recibida` | reportes | atencion, auditoria |
| `caso.actualizado` | atencion | reportes, indicadores, auditoria |
| `caso.cerrado` | atencion | reportes, indicadores, auditoria |

Los contratos crecen fase a fase; cada versión indica la fase en `info.version`.

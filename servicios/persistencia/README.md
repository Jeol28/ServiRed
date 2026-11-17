# Persistencia

**Único** servicio con credenciales de Supabase (ADR-02). Expone operaciones de negocio (`guardarReporte`, `actualizarEstadoCaso`, `registrarAuditoria`…), nunca un CRUD genérico. Gestiona transacciones, migraciones (Flyway), idempotencia por `X-Correlation-Id` y el **outbox** que publica los eventos en RabbitMQ después de confirmar la escritura (ADR-06). Contrato: [`contratos/internos/persistencia.v1.yaml`](../../contratos/internos/persistencia.v1.yaml).

| | |
|---|---|
| **Pila** | Java 21 · Spring Boot 3.5 · JDBC · Flyway · PostgreSQL (Supabase) |
| **Puerto** | 8080 (solo dentro de la red interna del compose) |
| **Health** | `/actuator/health` (readiness incluye base de datos y RabbitMQ) |
| **Métricas** | `/actuator/prometheus` |

## Estructura (arquitectura hexagonal)

```
src/main/java/co/edu/javeriana/servired/<servicio>/
  dominio/          entidades, reglas y puertos; sin Spring (verificado con ArchUnit)
  aplicacion/       casos de uso
  infraestructura/  adaptadores: web (entrada), persistencia y mensajería (salida)
```

## Ejecutar y probar

```bash
./mvnw verify   # levanta PostgreSQL y RabbitMQ con Testcontainers
```

Las pruebas de integración usan Testcontainers (ver el README raíz si usa Colima).
Para correrlo junto al resto: `docker compose up -d --build persistencia` desde la raíz.

## Esquemas por dominio
`catalogo`, `identidad`, `reportes`, `atencion`, `indicadores`, `auditoria` e
`infraestructura` (outbox, idempotencia e historial de Flyway).
Migraciones en `src/main/resources/db/migracion`.

## Operaciones de negocio

| Operación | Ruta interna | Quién puede invocarla |
|---|---|---|
| `guardarReporte` | `POST /interno/v1/reportes/guardar` | reportes |
| `consultarReporte` | `GET /interno/v1/reportes/{id}` | reportes |
| `abrirCaso`, `consultarCaso`, `listarCasos`, `actualizarCaso`, `buscarCobertura`, `verificarOperador`, `obtenerEvidenciaDeCaso` | `/interno/v1/casos/**`, `/interno/v1/catalogo/cobertura`, `/interno/v1/operadores/{id}` | atencion |
| `abrirModeracion`, `consultarModeracion`, `decidirModeracion`, `obtenerEvidenciaDenuncia` | `/interno/v1/moderaciones/**`, `/interno/v1/denuncias/{id}/evidencias/{id}` | atencion |
| `aplicarEstadoDeCaso` | `POST /interno/v1/reportes/{id}/estado-por-caso` | reportes |
| `resolverMunicipio` | `GET /interno/v1/catalogo/municipio-cercano` | reportes, atencion, indicadores |
| `listarReportesDeCiudadano`, `buscarReportesCercanos`, `registrarRestablecimiento`, `registrarValidacion`, `guardarEvidencia`, `obtenerEvidencia`, `guardarDenuncia` | `/interno/v1/reportes/**`, `/interno/v1/denuncias` | reportes |
| `registrarCuenta`, `buscarCredenciales`, `consultarCuenta`, `listarCuentas`, `actualizarCuenta`, `eliminarCuenta` | `/interno/v1/cuentas/**` | identidad |
| `abrirSesion`, `rotarSesion`, `cerrarSesion` | `/interno/v1/sesiones/**` | identidad |

- **Autenticación:** cada servicio envía su clave en `X-Clave-Servicio`
  (`PERSISTENCIA_CLAVE_<SERVICIO>`, mínimo 16 caracteres). Los permisos por
  operación están en el dominio (`OperacionNegocio`) y se prueban.
- **Idempotencia:** `X-Correlation-Id` es obligatorio; un reintento devuelve
  `200` con el recurso original (el primero devuelve `201`).
- **Outbox:** el evento recibido se guarda en la misma transacción. El relay
  (`RelayOutbox`, cada 500 ms) publica con *publisher confirms* y `mandatory`
  (un evento que ninguna cola recibe se considera fallido y se reintenta).
  Métricas: `servired_outbox_pendientes`, `servired_outbox_publicados_total`,
  `servired_outbox_fallos_total`.

## Evidencias
Fotos en un almacén **S3**: Supabase Storage en producción o SeaweedFS en local
(`ALMACEN_S3_*`). El objeto se sube en la transacción y se borra si la referencia
no se puede guardar. Readiness incluye el almacén. Ver
[ADR-13](../../docs/adr/ADR-13-evidencias-y-almacenamiento.md).

## Datos semilla
- **DIVIPOLA** (DANE, datos.gov.co conjunto `gdxc-w37w`): 33 departamentos y
  1.122 municipios y áreas no municipalizadas con su centroide. La migración
  `V3__semilla_divipola.sql` se regenera con
  `python3 herramientas/generar-semilla-divipola.py divipola.csv`.
- Tipos de servicio y **empresas de ejemplo ficticias** con cobertura en seis
  ciudades (`V4__semilla_servicios_empresas.sql`).

## Variables
`PERSISTENCIA_DB_URL`, `PERSISTENCIA_DB_USUARIO`, `PERSISTENCIA_DB_CLAVE`,
`PERSISTENCIA_CLAVE_<SERVICIO>`, `ALMACEN_S3_*` y `RABBITMQ_*` (ver `.env.ejemplo`).

## Estado
- Fase 1 ✅ esquemas, tablas de outbox e idempotencia, health y métricas.
- Fase 2 ✅ reportes, casos, catálogo, relay del outbox y datos semilla.
- Fase 3 ✅ cuentas y sesiones (esquema `identidad`).
- Fase 5 ✅ casos con versión optimista, historia, moderación y estado del reporte por caso.
- Fase 4 ✅ restablecimiento, validaciones (concurrencia optimista), evidencias en S3 y denuncias.

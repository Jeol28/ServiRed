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
| `abrirCaso` | `POST /interno/v1/casos/abrir` | atencion |
| `resolverMunicipio` | `GET /interno/v1/catalogo/municipio-cercano` | reportes, atencion, indicadores |

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

## Datos semilla
- **DIVIPOLA** (DANE, datos.gov.co conjunto `gdxc-w37w`): 33 departamentos y
  1.122 municipios y áreas no municipalizadas con su centroide. La migración
  `V3__semilla_divipola.sql` se regenera con
  `python3 herramientas/generar-semilla-divipola.py divipola.csv`.
- Tipos de servicio y **empresas de ejemplo ficticias** con cobertura en seis
  ciudades (`V4__semilla_servicios_empresas.sql`).

## Variables
`PERSISTENCIA_DB_URL`, `PERSISTENCIA_DB_USUARIO`, `PERSISTENCIA_DB_CLAVE`,
`PERSISTENCIA_CLAVE_<SERVICIO>` y `RABBITMQ_*` (ver `.env.ejemplo`).

## Estado
- Fase 1 ✅ esquemas, tablas de outbox e idempotencia, health y métricas.
- Fase 2 ✅ operaciones de la tabla anterior, relay del outbox y datos semilla.

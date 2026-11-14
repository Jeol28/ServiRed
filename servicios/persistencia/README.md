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

## Variables
`PERSISTENCIA_DB_URL`, `PERSISTENCIA_DB_USUARIO`, `PERSISTENCIA_DB_CLAVE` y `RABBITMQ_*` (ver `.env.ejemplo`).

## Estado
- Fase 1 ✅ esquemas, tablas de outbox e idempotencia, health y métricas.
- Fase 2: `guardarReporte`, `consultarReporte`, `resolverMunicipio`, relay del outbox y datos semilla.

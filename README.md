# SERVI-RED

Plataforma colaborativa de reporte ciudadano y monitoreo de servicios públicos
domiciliarios en Colombia (energía, gas, acueducto, alcantarillado y aseo).
Proyecto del curso **Arquitectura de Software**, Pontificia Universidad Javeriana.

- **Arquitectura:** [SAD de la Entrega 1](docs/) (RF-1 a RF-13, RNF, ADR-01 a ADR-04, vistas C4)
  y [ADR-05 en adelante](docs/adr/README.md) (decisiones de implementación).
- **Contratos:** [`contratos/`](contratos/README.md) — OpenAPI y eventos.

## Arquitectura en una imagen

```
 App Android · App iOS · Portal operador · Portal público
                         │  (solo HTTP al gateway)
                    ┌────▼────┐
                    │ gateway │  JWT · RBAC · rate limit · X-Correlation-Id
                    └────┬────┘
      ┌──────────┬───────┼──────────┬─────────────┐
 identidad   reportes   atencion   indicadores    auditoria
  (Java)      (Java)     (.NET)      (.NET)        (.NET)
      │          │          │           │             │
      └──────────┴────┬─────┴───────────┴─────────────┘
                      │ REST interno (operaciones de negocio)
               ┌──────▼───────┐   outbox    ┌──────────┐
               │ persistencia │────────────▶│ RabbitMQ │──▶ consumidores
               │    (Java)    │             └──────────┘
               └──────┬───────┘
                      ▼
          Supabase (PostgreSQL + Storage)
```

Solo **persistencia** tiene credenciales de la base de datos. Reportes y
Atención nunca se llaman entre sí: se comunican por eventos.

## Estructura

| Carpeta | Contenido |
|---|---|
| [`servicios/gateway`](servicios/gateway/README.md) | API Gateway (Spring Cloud Gateway) |
| [`servicios/identidad`](servicios/identidad/README.md) | Cuentas, sesiones, JWT y RBAC (RF-11) |
| [`servicios/reportes`](servicios/reportes/README.md) | Cortes, restablecimientos, validación y denuncias (RF-1 a RF-5) |
| [`servicios/persistencia`](servicios/persistencia/README.md) | Único acceso a Supabase; outbox e idempotencia |
| [`servicios/atencion`](servicios/atencion/README.md) | Casos del operador y moderación (RF-6, RF-7) |
| [`servicios/indicadores`](servicios/indicadores/README.md) | Vista de lectura, indicadores, mapas y datos abiertos (RF-8 a RF-10) |
| [`servicios/auditoria`](servicios/auditoria/README.md) | Auditoría y alertas (RF-12, RF-13) |
| [`clientes/`](clientes/README.md) | Web (Angular), Android (Kotlin) e iOS (Swift) |
| [`contratos/`](contratos/README.md) | OpenAPI de cada servicio y esquemas de eventos |
| [`infra/`](infra/README.md) | Docker Compose y topología de RabbitMQ |
| [`docs/`](docs/) | Enunciado, SAD y ADR |

## Requisitos

| Herramienta | Versión | Para |
|---|---|---|
| Docker + Docker Compose (con buildx) | Compose ≥ 2.20 | Levantar todo |
| JDK | 21 o superior | Compilar y probar los servicios Java (Maven va por wrapper) |
| .NET SDK | 10.0 | Compilar y probar los servicios .NET |

## Cómo correr todo (un solo comando)

```bash
cp .env.ejemplo .env          # una sola vez; cambie las claves
docker compose up -d --build --wait
```

Con eso quedan arriba PostgreSQL local, RabbitMQ (con su topología), los siete
microservicios y el gateway en **http://localhost:8080**.

```bash
curl -i http://localhost:8080/actuator/health        # gateway
python3 infra/rabbitmq/verificar-topologia.py        # prueba de humo de RabbitMQ
python3 pruebas/e2e/flujo-reporte.py                 # prueba de punta a punta (fase 2)
docker compose logs -f reportes                      # logs JSON de un servicio
docker compose down                                  # detener (agregue -v para borrar datos)
```

- Consola de RabbitMQ: http://127.0.0.1:15672 (usuario y clave de `.env`).
- Ningún microservicio, ni la base de datos, publica puertos: solo el gateway.

### Usar Supabase en lugar del PostgreSQL local
En `.env`, cambie `PERSISTENCIA_DB_URL`, `PERSISTENCIA_DB_USUARIO` y
`PERSISTENCIA_DB_CLAVE` por los datos del *Session pooler* de su proyecto
(formato JDBC, con `sslmode=require`). Persistencia aplica las migraciones al arrancar.

## Pruebas

```bash
# Java (cada servicio): unitarias, arquitectura e integración con Testcontainers
cd servicios/reportes && ./mvnw verify

# .NET (cada servicio)
cd servicios/atencion && dotnet test Atencion.slnx

# De punta a punta, con el compose arriba (usa el PostgreSQL local)
python3 pruebas/e2e/flujo-reporte.py
```

La prueba e2e crea, repite (idempotencia) y consulta un reporte por el gateway,
verifica que Atención abra el caso, que el `X-Correlation-Id` aparezca en los
logs de los cuatro servicios y que, **con Atención detenida, el reporte se
registre igual** y el caso se abra al volver (RNF-DC2).

Las pruebas de integración usan Docker (Testcontainers). Con **Colima** exporte antes:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Convenciones transversales

- **Correlación:** `X-Correlation-Id` (UUID) en HTTP, eventos y logs; es la clave de idempotencia.
- **Eventos:** exchange topic `servired.eventos`, routing key `<agregado>.<evento>.v<N>`,
  colas quorum con reintento y DLQ ([ADR-10](docs/adr/ADR-10-topologia-rabbitmq.md)).
- **Observabilidad:** logs JSON por stdout sin datos personales; health
  (`/actuator/health` en Java, `/health/live` y `/health/ready` en .NET) y métricas
  Prometheus (`/actuator/prometheus` y `/metrics`).
- **Secretos:** solo en `.env` (ignorado por git). `.env.ejemplo` documenta cada variable.
- **Idioma:** código, commits y documentación en español.

## Plan por fases

| Fase | Alcance | Estado |
|---|---|---|
| 1 | Monorepo, Docker Compose, RabbitMQ, esqueletos con health, contratos iniciales | ✅ |
| 2 | Corte vertical: crear y consultar reporte + `reporte.creado` consumido por atención | ✅ |
| 3 | Identidad: registro, login, JWT y RBAC en el gateway | ⏳ |
| 4 | Reportes completo: restablecimiento, validación, evidencias, denuncias | |
| 5 | Atención: casos, estados, asignación | |
| 6 | Indicadores: vista de lectura, indicadores, mapas, exportación | |
| 7 | Auditoría y alertas | |
| 8 | Portales Angular | |
| 9 | App Android | |
| 10 | App iOS | |

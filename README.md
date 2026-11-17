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

Con eso quedan arriba PostgreSQL local, el almacén de evidencias (SeaweedFS), RabbitMQ (con su topología), los siete
microservicios y el gateway en **http://localhost:8080**.

```bash
curl -i http://localhost:8080/readyz                 # gateway
python3 infra/rabbitmq/verificar-topologia.py        # prueba de humo de RabbitMQ
python3 pruebas/e2e/flujo-reporte.py                 # e2e: reporte de punta a punta
python3 pruebas/e2e/reportes-completo.py             # e2e: fotos, validación, restablecimiento, denuncias
python3 pruebas/e2e/atencion-casos.py                # e2e: casos, operador, moderación y estado del ciudadano
python3 pruebas/e2e/indicadores.py                   # e2e: indicadores, mapas, datos abiertos y exportación
python3 pruebas/e2e/auditoria-alertas.py             # e2e: auditoría, alertas y (con el perfil) observabilidad
python3 pruebas/e2e/identidad-y-acceso.py            # e2e: cuentas, tokens y RBAC
docker compose logs -f reportes                      # logs JSON de un servicio
docker compose down                                  # detener (agregue -v para borrar datos)
```

- Consola de RabbitMQ: http://127.0.0.1:15672 (usuario y clave de `.env`).
- Primer administrador: `IDENTIDAD_ADMIN_CORREO` / `IDENTIDAD_ADMIN_CLAVE` de `.env`.
- Clave de firma de los JWT: `echo "IDENTIDAD_JWT_CLAVE_PRIVADA=$(sh infra/generar-clave-jwt.sh)"`
  y péguela en `.env` (si se deja vacía se usa una clave efímera).
- Ningún microservicio, ni la base de datos, publica puertos: solo el gateway.

### Observabilidad (opcional)
`TRAZAS_ACTIVAS=true docker compose --profile observabilidad up -d --build --wait` agrega
Prometheus (127.0.0.1:9090), Alertmanager (:9093), Grafana (:3000, usuario `admin` y
`GRAFANA_CLAVE`) y Jaeger (:16686). Ver [infra/README.md](infra/README.md).

### Almacén de evidencias
Por defecto las fotos van a **SeaweedFS** (S3 compatible) dentro del compose. Para
**Supabase Storage**, cree un bucket privado y claves S3 en el panel del proyecto y
cambie en `.env`: `ALMACEN_S3_URL=https://<proyecto>.supabase.co/storage/v1/s3`,
`ALMACEN_S3_REGION`, `ALMACEN_S3_ACCESO`, `ALMACEN_S3_SECRETO` y
`ALMACEN_S3_CREAR_BUCKET=false`.

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
python3 pruebas/e2e/reportes-completo.py
python3 pruebas/e2e/atencion-casos.py
python3 pruebas/e2e/indicadores.py
python3 pruebas/e2e/auditoria-alertas.py
python3 pruebas/e2e/identidad-y-acceso.py
```

La prueba e2e crea, repite (idempotencia) y consulta un reporte por el gateway,
verifica que Atención abra el caso, que el `X-Correlation-Id` aparezca en los
logs de los cuatro servicios y que, **con Atención detenida, el reporte se
registre igual** y el caso se abra al volver (RNF-DC2). La de identidad prueba
registro, login, tokens sin datos personales, mínimo privilegio por rol, rotación
del token de refresco, desactivación, supresión, ausencia de secretos en los logs
y el límite de abuso.

Las pruebas de integración usan Docker (Testcontainers). Con **Colima** exporte antes:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Convenciones transversales

- **Correlación:** `X-Correlation-Id` (UUID) en HTTP, eventos y logs; es la clave de idempotencia.
- **Eventos:** exchange topic `servired.eventos`, routing key `<agregado>.<evento>.v<N>`,
  colas quorum con reintento y DLQ ([ADR-10](docs/adr/ADR-10-topologia-rabbitmq.md)).
- **Seguridad:** JWT RS256 de Identidad validado en el gateway y en cada servicio;
  RBAC con mínimo privilegio ([ADR-12](docs/adr/ADR-12-identidad-y-control-de-acceso.md)).
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
| 3 | Identidad: registro, login, JWT y RBAC en el gateway | ✅ |
| 4 | Reportes completo: restablecimiento, validación, evidencias, denuncias | ✅ |
| 5 | Atención: casos, estados, asignación | ✅ |
| 6 | Indicadores: vista de lectura, indicadores, mapas, exportación | ✅ |
| 7 | Auditoría y alertas | ✅ |
| 8 | Portales Angular | ⏳ |
| 9 | App Android | |
| 10 | App iOS | |

# Gestión de reportes

Núcleo del flujo ciudadano: cortes no programados (RF-1), confirmación y consulta de estado e historia (RF-2), restablecimiento y duración (RF-3), validación comunitaria (RF-4) y denuncias anónimas (RF-5). Persiste a través de persistencia y es el dueño de los eventos `reporte.creado`, `restablecimiento.registrado`, `validacion.registrada` y `denuncia.recibida`. Consume `caso.actualizado` y `caso.cerrado` (ADR-07).

| | |
|---|---|
| **Pila** | Java 21 · Spring Boot 3.5 · Spring AMQP |
| **Puerto** | 8080 (solo dentro de la red interna del compose) |
| **Health** | `/actuator/health` (readiness incluye RabbitMQ) |
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
./mvnw verify
```

Las pruebas de integración usan Testcontainers (ver el README raíz si usa Colima).
Para correrlo junto al resto: `docker compose up -d --build reportes` desde la raíz.

## API (detrás del gateway)

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/v1/reportes` | Registrar un corte (RF-1). `201` + `Location`; idempotente por `X-Correlation-Id` |
| `GET` | `/api/v1/reportes/{id}` | Estado e historia (RF-2); `404` si no existe o no es del ciudadano |
| `GET` | `/api/v1/reportes` | Mis reportes, paginados |
| `GET` | `/api/v1/reportes/cercanos?latitud&longitud` | Eventos activos a ≤ 1 km para validar (sin autor ni descripción) |
| `POST` | `/api/v1/reportes/{id}/restablecimiento` | RF-3: el autor registra el restablecimiento; se calcula la duración |
| `POST` | `/api/v1/reportes/{id}/validaciones` | RF-4: confirmar o controvertir; 3 confirmaciones netas validan |
| `POST` | `/api/v1/reportes/{id}/evidencias` | RF-1: foto JPEG/PNG (multipart `foto`), sin EXIF ni GPS, máximo 3 |
| `GET` | `/api/v1/reportes/{id}/evidencias/{idEvidencia}` | Descargar la foto (solo el autor) |
| `POST` | `/api/v1/denuncias` | RF-5: denuncia anónima, pública (JSON o multipart con `foto`) |

Errores en `application/problem+json` con la lista `errores` (campo y mensaje).
Si Persistencia no responde tras 3 intentos se devuelve `503` (es seguro reintentar
con el mismo `X-Correlation-Id`).

**Reglas (RNF-DAT1, ADR-08):** ubicación dentro de Colombia; inicio no futuro
(tolerancia de 5 min) y de máximo 30 días; descripción de hasta 1.000 caracteres.
El municipio se resuelve en Persistencia (centroide DIVIPOLA más cercano).

**Seguridad (fase 3):** revalida el JWT con el JWKS de Identidad (emisor y
audiencia); solo el rol CIUDADANO accede, y el ciudadano es el claim `sub`
(`CiudadanoActual`). Un ciudadano solo ve sus propios reportes.

## Variables
`PERSISTENCIA_URL`, `PERSISTENCIA_CLAVE` (en el compose viene de
`PERSISTENCIA_CLAVE_REPORTES`), `IDENTIDAD_JWKS_URL` y `RABBITMQ_*`.

## Estado
- Fase 1 ✅ esqueleto, correlación, health (con RabbitMQ) y métricas.
- Fase 2 ✅ crear y consultar reporte de punta a punta con `reporte.creado`.
- Fase 3 ✅ JWT revalidado y ciudadano tomado del token.
- Fase 5 ✅ consume `caso.actualizado` y `caso.cerrado` (cola `reportes.eventos-casos`) y actualiza el estado del ciudadano ([ADR-07](../../docs/adr/ADR-07-estado-del-reporte-por-eventos.md)).
- Fase 4 ✅ restablecimiento, validación comunitaria, evidencias saneadas y denuncias anónimas
  ([ADR-08](../../docs/adr/ADR-08-reglas-de-dominio.md), [ADR-09](../../docs/adr/ADR-09-denuncias-anonimas.md),
  [ADR-13](../../docs/adr/ADR-13-evidencias-y-almacenamiento.md)).

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

Errores en `application/problem+json` con la lista `errores` (campo y mensaje).
Si Persistencia no responde tras 3 intentos se devuelve `503` (es seguro reintentar
con el mismo `X-Correlation-Id`).

**Reglas (RNF-DAT1, ADR-08):** ubicación dentro de Colombia; inicio no futuro
(tolerancia de 5 min) y de máximo 30 días; descripción de hasta 1.000 caracteres.
El municipio se resuelve en Persistencia (centroide DIVIPOLA más cercano).

> **Provisional (fase 2):** sin Identidad todavía, todas las peticiones se
> atribuyen al ciudadano de desarrollo `servired.desarrollo.id-ciudadano`
> (`CiudadanoActual`). La fase 3 lo reemplaza por el `sub` del JWT.

## Variables
`PERSISTENCIA_URL`, `PERSISTENCIA_CLAVE` (en el compose viene de
`PERSISTENCIA_CLAVE_REPORTES`) y `RABBITMQ_*`.

## Estado
- Fase 1 ✅ esqueleto, correlación, health (con RabbitMQ) y métricas.
- Fase 2 ✅ crear y consultar reporte de punta a punta con `reporte.creado`.
- Fase 4: restablecimiento, validación, evidencias y denuncias.

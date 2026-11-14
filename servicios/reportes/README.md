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

## Estado
- Fase 1 ✅ esqueleto, correlación, health (con RabbitMQ) y métricas.
- Fase 2: crear y consultar reporte de punta a punta.
- Fase 4: restablecimiento, validación, evidencias y denuncias.

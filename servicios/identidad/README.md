# Identidad y acceso

Administra cuentas de ciudadanos, operadores, reguladores y administradores (RF-11), sesiones y tokens JWT, y el modelo RBAC con mínimo privilegio (RNF-SEG2).

| | |
|---|---|
| **Pila** | Java 21 · Spring Boot 3.5 |
| **Puerto** | 8080 (solo dentro de la red interna del compose) |
| **Health** | `/actuator/health` (readiness: `/actuator/health/readiness`) |
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
Para correrlo junto al resto: `docker compose up -d --build identidad` desde la raíz.

## Estado
- Fase 1 ✅ esqueleto, correlación, health y métricas.
- Fase 3: registro, login, JWT (RS256 + JWKS), refresco, revocación y CRUD de cuentas.

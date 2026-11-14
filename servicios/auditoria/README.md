# Auditoría

Consume todos los eventos (`#`) y registra auditoría de accesos, cambios de estado, validaciones, exportaciones y acciones administrativas (RF-12). Genera alertas de seguridad (RF-13). No se expone por el gateway.

| | |
|---|---|
| **Pila** | C# · .NET 10 · ASP.NET Core · RabbitMQ.Client 7 |
| **Puerto** | 8080 (solo dentro de la red interna del compose) |
| **Health** | `/health/live` y `/health/ready` (incluye RabbitMQ) |
| **Métricas** | `/metrics` |

## Estructura (arquitectura hexagonal)

```
src/
  <Servicio>.Dominio/          entidades y reglas; sin referencias a framework
  <Servicio>.Aplicacion/       casos de uso y puertos
  <Servicio>.Infraestructura/  adaptadores de salida (RabbitMQ, cliente de Persistencia)
  <Servicio>.Api/              adaptador HTTP de entrada y composición
tests/<Servicio>.Pruebas/      unitarias, arquitectura (NetArchTest) e integración
```

## Ejecutar y probar

```bash
dotnet test Auditoria.slnx
```

Las pruebas de integración usan Testcontainers (ver el README raíz si usa Colima).
Para correrlo junto al resto: `docker compose up -d --build auditoria` desde la raíz.

## Estado
- Fase 1 ✅ esqueleto hexagonal, correlación, health y métricas.
- Fase 7: registro de auditoría y alertas.

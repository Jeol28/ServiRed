# Atención operacional

Gestiona los casos del operador: crea el caso al recibir `reporte.creado`, lo enruta a la empresa operadora responsable por servicio y municipio (RF-6) y permite filtrar, priorizar, asignar, actualizar y cerrar (RF-7). Modera las denuncias anónimas. Publica `caso.actualizado` y `caso.cerrado`.

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
dotnet test Atencion.slnx
dotnet run --project src/Atencion.Api
```

Las pruebas de integración usan Testcontainers (ver el README raíz si usa Colima).
Para correrlo junto al resto: `docker compose up -d --build atencion` desde la raíz.

## Estado
- Fase 1 ✅ esqueleto hexagonal, correlación, health y métricas.
- Fase 2: consumidor idempotente de `reporte.creado`.
- Fase 5: casos, estados, asignación y moderación.

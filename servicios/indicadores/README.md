# Indicadores y datos abiertos

Construye su propia vista de lectura a partir de eventos (sin consultar a reportes ni a atención), calcula indicadores de frecuencia, duración, severidad y distribución geográfica (RF-9), prepara mapas (RF-8), anonimiza y exporta datos regulatorios y abiertos (RF-10, RNF-DAT2).

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
dotnet test Indicadores.slnx
```

Las pruebas de integración usan Testcontainers (ver el README raíz si usa Colima).
Para correrlo junto al resto: `docker compose up -d --build indicadores` desde la raíz.

## Estado
- Fase 1 ✅ esqueleto hexagonal, correlación, health y métricas.
- Fase 6: vista de lectura, indicadores, mapas y exportación CSV/JSON.

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

## API

| Método y ruta | Acceso | Descripción |
|---|---|---|
| `GET /api/v1/auditoria/registros` | regulador, administrador | Filtros `categoria`, `accion`, `actorId`, `recursoId`, `desde`, `hasta`, `pagina`, `tamano` |
| `GET /api/v1/auditoria/alertas` | regulador, administrador | Registros de categoría `ALERTA` |
| `GET /api/v1/auditoria/integridad` | administrador | Verifica la cadena de hashes |
| `POST /interno/v1/alertas` | Alertmanager (red interna, `Authorization: Bearer ALERTAS_TOKEN`) | Webhook de alertas operativas |

## Funcionamiento
- Consume **todos** los eventos (`auditoria.eventos`, binding `#`) y los registra
  saneados en Persistencia (`auditoria.registros`, solo inserción, encadenado).
- Categorías: ACCESO, ADMINISTRACION, EXPORTACION, MODERACION, SEGURIDAD,
  CAMBIO_ESTADO, VALIDACION, REPORTE y ALERTA.
- Detector de alertas de seguridad y métrica `servired_alertas_seguridad_total`.
Decisiones: [ADR-16](../../docs/adr/ADR-16-auditoria-y-alertas.md).

## Variables
`PERSISTENCIA_URL`, `PERSISTENCIA_CLAVE` (de `PERSISTENCIA_CLAVE_AUDITORIA`),
`ALERTAS_TOKEN`, `IDENTIDAD_JWKS_URL`, `TRAZAS_ACTIVAS` y `RABBITMQ_*`.

## Estado
- Fase 1 ✅ esqueleto hexagonal, correlación, health y métricas.
- Fase 7 ✅ registro de auditoría encadenado, alertas de seguridad y webhook de Alertmanager.

# Atención operacional

Gestiona los casos del operador: crea el caso al recibir `reporte.creado`, lo enruta a la empresa operadora responsable por servicio y municipio (RF-6) y permite filtrar, priorizar, asignar, actualizar y cerrar (RF-7). Modera las denuncias anónimas. Publica `caso.actualizado` (también al abrir el caso, sin `estadoAnterior`; ADR-24) y `caso.cerrado`.

| | |
|---|---|
| **Pila** | C# · .NET 10 · ASP.NET Core · RabbitMQ.Client 7 |
| **Puerto** | 8080 (solo dentro de la red interna del compose) |
| **Health** | `/health/live` y `/health/ready` (RabbitMQ y consumidor activo) |
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

## API (detrás del gateway)

| Método y ruta | Quién | Descripción |
|---|---|---|
| `GET /api/v1/casos` | operador (su empresa), regulador, administrador | Bandeja ordenada por prioridad; filtros `estado`, `tipoServicio`, `codigoMunicipio`, `prioridad`, `idEmpresa`, `sinEmpresa`, `asignadoA`, `mios` |
| `GET /api/v1/casos/{id}` | ídem | Caso con historia y evidencias |
| `GET /api/v1/casos/{id}/evidencias/{idEvidencia}` | ídem | Foto del reporte |
| `POST /api/v1/casos/{id}/atender` · `/resolver` · `/cerrar` | operador, administrador | Ciclo de vida |
| `PUT /api/v1/casos/{id}/asignacion` · `/prioridad` | operador, administrador | Asignar (a sí mismo o a un colega) y fijar prioridad |
| `PUT /api/v1/casos/{id}/empresa` | administrador | Enrutar manualmente |
| `GET /api/v1/moderacion/denuncias[/{id}]` | operador (su empresa), regulador, administrador | Denuncias en moderación |
| `POST /api/v1/moderacion/denuncias/{id}/decision` | operador, administrador | VALIDADA o DESCARTADA con nota |

Reglas de enrutamiento, prioridad, transiciones y acceso: [ADR-14](../../docs/adr/ADR-14-atencion-de-casos.md).
El JWT se revalida con el JWKS de Identidad (`IDENTIDAD_JWKS_URL`).

## Consumo de eventos
Cola `atencion.eventos-reportes`:

| Evento | Reacción |
|---|---|
| `reporte.creado.v1` | Abre el caso enrutado y priorizado (un caso por reporte) y publica su `caso.actualizado` de apertura en la misma transacción |
| `restablecimiento.registrado.v1` | El caso abierto pasa a `RESUELTO` y publica `caso.actualizado` |
| `validacion.registrada.v1` (CORTE) | Marca la validación y recalcula la prioridad |
| `denuncia.recibida.v1` | Abre la moderación en la empresa con cobertura |

Reintentos, DLQ y tipos ignorados según [ADR-10](../../docs/adr/ADR-10-topologia-rabbitmq.md). Métrica:
`servired_eventos_procesados_total{tipo,resultado}`.

## Variables
`PERSISTENCIA_URL`, `PERSISTENCIA_CLAVE` (en el compose viene de
`PERSISTENCIA_CLAVE_ATENCION`), `IDENTIDAD_JWKS_URL`, `CONSUMIDOR_MAX_INTENTOS` (5), `CONSUMIDOR_PREFETCH` (10)
y `RABBITMQ_*`.

## Estado
- Fase 1 ✅ esqueleto hexagonal, correlación, health y métricas.
- Fase 2 ✅ consumidor de `reporte.creado` con reintento y DLQ; readiness incluye el consumidor.
- Fase 5 ✅ enrutamiento, prioridad, ciclo de vida, asignación, panel del operador y moderación.

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

## API (detrás del gateway)

| Método y ruta | Acceso | Descripción |
|---|---|---|
| `GET /api/v1/indicadores/catalogo` | público | Definiciones, fuente y política de anonimización |
| `GET /api/v1/indicadores/resumen` | público | Frecuencia, duración, severidad y atención (total y por servicio) |
| `GET /api/v1/indicadores/territorial?nivel=DEPARTAMENTO\|MUNICIPIO` | público | Distribución geográfica |
| `GET /api/v1/mapas/publico` | público | Celdas de ~1,1 km con ≥ 3 reportes (RF-8) |
| `GET /api/v1/datos-abiertos/catalogo` | público | Conjuntos abiertos y metadatos |
| `GET /api/v1/datos-abiertos/indicadores-municipales?formato=csv\|json` | público | Municipio × servicio × mes (RF-10) |
| `GET /api/v1/mapas/operativo` | operador (su empresa), regulador, administrador | Puntos con estado y prioridad |
| `GET /api/v1/indicadores/exportaciones/reportes?formato=csv\|json` | regulador, administrador | Exportación regulatoria |

Filtros comunes: `desde`, `hasta` (ISO 8601; por defecto los últimos 30 días),
`tipoServicio`, `codigoDepartamento`, `codigoMunicipio`, `situacion` (`ACTIVO`/`RESUELTO`).
Toda respuesta pública lleva `metadatos` (RNF-DAT4). Anonimización y definiciones:
[ADR-15](../../docs/adr/ADR-15-indicadores-y-datos-abiertos.md).

## Consumo de eventos
Cola `indicadores.eventos` (`reporte.#`, `restablecimiento.#`, `validacion.#`, `caso.#`):
proyecta cada evento en la vista de lectura de Persistencia. Misma política de
reintento y DLQ que Atención ([ADR-10](../../docs/adr/ADR-10-topologia-rabbitmq.md)).

## Variables
`PERSISTENCIA_URL`, `PERSISTENCIA_CLAVE` (de `PERSISTENCIA_CLAVE_INDICADORES`),
`IDENTIDAD_JWKS_URL` y `RABBITMQ_*`.

## Estado
- Fase 1 ✅ esqueleto hexagonal, correlación, health y métricas.
- Fase 6 ✅ vista de lectura, indicadores, mapas, datos abiertos (supresión k = 3) y exportación regulatoria.

# Gateway

Único punto de entrada de los clientes (portales y apps). Enruta hacia los microservicios, garantiza el `X-Correlation-Id` y, desde la fase 3, valida JWT, aplica RBAC por ruta y límites de uso. **Nunca** enruta hacia persistencia; de auditoría solo la consulta (`GET /api/v1/auditoria/**`).

| | |
|---|---|
| **Pila** | Java 21 · Spring Cloud Gateway 2025.0 (WebFlux) |
| **Puerto** | 8080 (solo dentro de la red interna del compose; publicado en el host) |
| **Health** | Público: `/livez`, `/readyz`. Gestión (8081, no publicado): `/actuator/health` |
| **Métricas** | `/actuator/prometheus` en el puerto de gestión 8081 (no publicado) |

## Estructura (arquitectura hexagonal)

```
src/main/java/co/edu/javeriana/servired/<servicio>/
  dominio/          entidades, reglas y puertos; sin Spring (verificado con ArchUnit)
  aplicacion/       casos de uso
  infraestructura/  adaptadores: web (entrada), persistencia y mensajería (salida)
```

## Ejecutar y probar

```bash
./mvnw verify            # pruebas
./mvnw spring-boot:run    # local en :8080
```

Las pruebas de integración usan Testcontainers (ver el README raíz si usa Colima).
Para correrlo junto al resto: `docker compose up -d --build gateway` desde la raíz.

## Rutas

| Prefijo | Destino |
|---|---|
| `/api/v1/auth/**`, `/api/v1/cuentas/**` | identidad |
| `/api/v1/reportes/**` | reportes |
| `/api/v1/denuncias/**` | reportes, **sin** `Authorization` ni `Cookie` (ADR-09) |
| `/api/v1/casos/**`, `/api/v1/moderacion/**` | atencion |
| `GET /api/v1/auditoria/**` | auditoria (solo lectura) |
| `/api/v1/indicadores/**`, `/api/v1/mapas/**`, `/api/v1/datos-abiertos/**` | indicadores |

## Correlación
`FiltroCorrelacion` (WebFilter) acepta el `X-Correlation-Id` del cliente solo si es un
UUID válido; si no, genera uno. Lo propaga al destino, lo devuelve en la respuesta y
lo registra en un log de acceso JSON (método, ruta, estado y duración; nunca IP).

## Seguridad
- **JWT:** valida firma RS256 con el JWKS de Identidad (`IDENTIDAD_JWKS_URL`),
  emisor, audiencia y expiración. Reenvía el token a los servicios, que lo revalidan.
- **RBAC:** la matriz está en el dominio (`ReglasAcceso`) y se documenta en
  [ADR-12](../../docs/adr/ADR-12-identidad-y-control-de-acceso.md). Lo no declarado se niega.
- **Límites (RNF-SEG5):** `/api/v1/auth/**` 20/min y `/api/v1/denuncias/**` 5/min
  por cliente (hash salado de la IP, solo en memoria). Responde 429 con `Retry-After`.
  Detrás de los portales, la IP del cliente se toma de `X-Forwarded-For` **solo** si la
  conexión viene de un proxy de `PROXIES_CONFIABLES` (nombres de host; ver
  [ADR-17](../../docs/adr/ADR-17-portales-web.md)); de cualquier otro origen se ignora.
- **Minimización:** no agrega ni reenvía `X-Forwarded-For`, `X-Real-IP` ni `Forwarded`
  a los servicios: la IP del cliente no sale del gateway.
- Errores 401, 403 y 429 en `application/problem+json` con `correlationId`.

## Estado
- Fase 1 ✅ enrutamiento, correlación, health y métricas.
- Fase 3 ✅ JWT, RBAC por ruta, límite de abuso y actuator en puerto de gestión.
- Fase 8 ✅ proxies confiables para el límite por cliente detrás de los portales.

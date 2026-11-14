# Gateway

Único punto de entrada de los clientes (portales y apps). Enruta hacia los microservicios, garantiza el `X-Correlation-Id` y, desde la fase 3, valida JWT, aplica RBAC por ruta y límites de uso. **Nunca** enruta hacia persistencia ni auditoría.

| | |
|---|---|
| **Pila** | Java 21 · Spring Cloud Gateway 2025.0 (WebFlux) |
| **Puerto** | 8080 (solo dentro de la red interna del compose; publicado en el host) |
| **Health** | `/actuator/health` |
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
./mvnw verify            # pruebas
./mvnw spring-boot:run    # local en :8080
```

Las pruebas de integración usan Testcontainers (ver el README raíz si usa Colima).
Para correrlo junto al resto: `docker compose up -d --build gateway` desde la raíz.

## Rutas

| Prefijo | Destino |
|---|---|
| `/api/v1/auth/**`, `/api/v1/cuentas/**` | identidad |
| `/api/v1/reportes/**`, `/api/v1/denuncias/**` | reportes |
| `/api/v1/casos/**` | atencion |
| `/api/v1/indicadores/**`, `/api/v1/mapas/**`, `/api/v1/datos-abiertos/**` | indicadores |

## Correlación
`FiltroCorrelacion` (WebFilter) acepta el `X-Correlation-Id` del cliente solo si es un
UUID válido; si no, genera uno. Lo propaga al destino, lo devuelve en la respuesta y
lo registra en un log de acceso JSON (método, ruta, estado y duración; nunca IP).

## Estado
- Fase 1 ✅ enrutamiento, correlación, health y métricas.
- Fase 3: JWT, RBAC y rate limiting.

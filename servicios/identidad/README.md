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

## API (detrás del gateway)

| Método y ruta | Quién | Descripción |
|---|---|---|
| `POST /api/v1/auth/registro` | público | Registro de ciudadano (exige `aceptaTratamientoDatos: true`) |
| `POST /api/v1/auth/login` | público | Devuelve `tokenAcceso` (JWT, 15 min) y `tokenRefresco` (14 días, un uso) |
| `POST /api/v1/auth/refrescar` | público | Rota el token de refresco |
| `POST /api/v1/auth/cerrar-sesion` | público | Revoca el token de refresco |
| `GET/PATCH/DELETE /api/v1/cuentas/yo` | autenticado | Cuenta propia: consultar, editar o cambiar clave, suprimir |
| `POST/GET /api/v1/cuentas`, `GET/PATCH/DELETE /api/v1/cuentas/{id}` | administrador | Administración de cuentas (RF-11) |
| `GET /.well-known/jwks.json` | red interna | Clave pública de firma para el gateway y los servicios |

Decisiones de tokens, claves y roles: [ADR-12](../../docs/adr/ADR-12-identidad-y-control-de-acceso.md).

## Estructura
- `dominio/`: `Rol`, `Correo`, `PoliticaClave`, `NuevaCuenta`, `ReglasAdministracion`.
- `aplicacion/`: `Autenticacion` (gestor de sesiones y tokens) y
  `AdministracionCuentas` (gestor de cuentas); puertos hacia Persistencia,
  cifrado de claves y emisión de tokens.
- `infraestructura/`: JWT con Nimbus (`EmisorJwt`, `ClavesFirma`), bcrypt,
  cliente de Persistencia, seguridad de Spring y administrador inicial.

## Variables
| Variable | Uso |
|---|---|
| `IDENTIDAD_JWT_CLAVE_PRIVADA` | RSA PKCS#8 DER en Base64. Generar con `sh infra/generar-clave-jwt.sh`. Vacía = clave efímera |
| `IDENTIDAD_ADMIN_CORREO`, `IDENTIDAD_ADMIN_CLAVE` | Primer administrador (se crea al arrancar si no existe) |
| `PERSISTENCIA_URL`, `PERSISTENCIA_CLAVE` | En el compose, la clave viene de `PERSISTENCIA_CLAVE_IDENTIDAD` |

## Estado
- Fase 1 ✅ esqueleto, correlación, health y métricas.
- Fase 3 ✅ registro, login, JWT RS256 con JWKS, refresco con rotación, revocación y administración de cuentas.

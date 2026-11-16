# ADR-12 · Identidad, tokens y control de acceso

**Estado:** aceptada · **Fecha:** 2026-11-16

## Contexto
El SAD pide un servicio de Identidad que emita, valide y revoque tokens,
controle su expiración y renovación (RF-11) y aplique RBAC con mínimo
privilegio y segregación entre ciudadano, operador y regulador (RNF-SEG1,
RNF-SEG2). La decisión F fijó que los servicios revalidan el JWT con JWKS.

## Decisión
- **Token de acceso:** JWT **RS256** de **15 minutos** firmado por Identidad.
  Claims: `iss=servired-identidad`, `aud=servired-api`, `sub` (id de la cuenta),
  `rol`, `emp` (empresa del operador), `iat`, `nbf`, `exp`, `jti`. **Sin correo ni
  nombre** (minimización). La clave privada vive solo en `.env`
  (`IDENTIDAD_JWT_CLAVE_PRIVADA`); la pública se publica en
  `/.well-known/jwks.json` con `kid` = huella RFC 7638.
- **Validación en capas:** el gateway valida firma, emisor, audiencia y
  expiración y aplica una matriz gruesa por ruta (`ReglasAcceso`); cada servicio
  vuelve a validar el JWT y aplica sus reglas finas (p. ej. Reportes: solo el
  dueño ve su reporte). Lo no declarado se niega.
- **Token de refresco:** opaco (256 bits), **14 días**, de **un solo uso**
  (rotación). Persistencia guarda solo su SHA-256. Presentar un token ya rotado
  revoca **todas** las sesiones de la cuenta (detección de reutilización).
- **Claves:** bcrypt (costo 12); política de 10 a 64 caracteres (máximo 72
  bytes, límite de bcrypt) y distinta del correo, sin reglas de composición
  (NIST SP 800-63B).
- **Inicio de sesión:** correo inexistente, clave errónea y cuenta desactivada
  responden igual (401) y con tiempos similares (se compara contra un hash
  ficticio).
- **Cuentas (RF-11):** el ciudadano se registra solo y debe autorizar el
  tratamiento de datos (Ley 1581 de 2012; se guarda la fecha). El personal lo
  crea un administrador; un operador pertenece a una empresa. Un administrador
  no puede desactivarse ni eliminarse a sí mismo. Eliminar **anonimiza** correo
  y nombre (los reportes conservan el identificador seudónimo).
- **Primer administrador:** se crea al arrancar desde `IDENTIDAD_ADMIN_CORREO`
  e `IDENTIDAD_ADMIN_CLAVE` si no existe.
- **Abuso (RNF-SEG5):** límite por ventana fija en `/api/v1/auth/**` (20/min) y
  `/api/v1/denuncias/**` (5/min) por cliente, identificado por un SHA-256 salado
  de la IP que solo vive en memoria.
- **Superficie pública:** actuator del gateway pasa al puerto de gestión 8081
  (no publicado); hacia fuera solo `/livez` y `/readyz`.

## Matriz de acceso (gateway)

| Ruta | Público | Ciudadano | Operador | Regulador | Administrador |
|---|---|---|---|---|---|
| `POST /api/v1/auth/*` | ✅ | | | | |
| `POST /api/v1/denuncias/**` (sin credenciales) | ✅ | | | | |
| `GET` indicadores, mapas, datos abiertos | ✅ | | | | |
| `/api/v1/reportes/**` | | ✅ | | | |
| `GET /api/v1/casos/**` | | | ✅ | ✅ | ✅ |
| Otros métodos en `/api/v1/casos/**` | | | ✅ | | ✅ |
| `/api/v1/cuentas/yo` | | ✅ | ✅ | ✅ | ✅ |
| `/api/v1/cuentas/**` | | | | | ✅ |

## Consecuencias
- Desactivar una cuenta revoca sus sesiones de inmediato, pero un token de
  acceso ya emitido vale hasta 15 minutos más. Se acepta a cambio de no consultar
  a Identidad en cada petición; una lista de revocación queda como mejora.
- Reintentar automáticamente un refresco con el mismo token cierra la sesión
  (se interpreta como reutilización). Los clientes no deben reintentarlo.
- Sin `IDENTIDAD_JWT_CLAVE_PRIVADA` se usa una clave efímera: cómodo en
  desarrollo, pero los tokens mueren al reiniciar Identidad.
- Detrás de un proxy, la IP vista por el gateway sería la del proxy; habría que
  confiar en `X-Forwarded-For` solo desde ese proxy.

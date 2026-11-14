# ADR-11 · Alcance del MVP: notificaciones, observabilidad y seguridad entre servicios

**Estado:** aceptada · **Fecha:** 2026-11-14

## Decisión
- **Notificaciones:** la confirmación de recepción (RF-2) es la respuesta síncrona
  de la creación; el cambio de estado se consulta en la app. Las notificaciones
  push quedan como trabajo futuro (no hay servicio de notificaciones en el MVP).
- **Observabilidad:** el contenedor "Observabilidad" del SAD se divide en
  - el servicio `auditoria` (RF-12 y alertas de seguridad de RF-13), y
  - un stack Prometheus + Grafana + Alertmanager + Jaeger en un perfil opcional
    del compose (alertas operativas de RF-13, RNF-OBS3, RNF-OBS4).
- **JWT:** el gateway valida el token y aplica RBAC por ruta; cada servicio vuelve
  a validar el JWT con la JWKS de identidad (defensa en profundidad).
- **Servicio a Persistencia:** clave propia por servicio (`X-Clave-Servicio`) y
  permisos por operación (mínimo privilegio). Persistencia no es alcanzable desde
  fuera de la red interna.
- **Supabase:** el compose apunta por defecto a un PostgreSQL local; Supabase se
  activa cambiando `PERSISTENCIA_DB_URL` en `.env`. Las pruebas usan Testcontainers.

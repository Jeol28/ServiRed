# ADR-16 · Auditoría a prueba de manipulación y alertas

**Estado:** reemplazada por [ADR-33](ADR-33-se-retira-la-auditoria.md) (la auditoría se retiró) · **Fecha:** 2026-11-17 · **Requisitos:** RF-12, RF-13, RNF-SEG4, RNF-OBS

## Contexto
RF-12 pide registrar accesos, cambios de estado, validaciones, exportaciones y
acciones administrativas; RF-13, monitorear procesos críticos y generar alertas
operativas y de seguridad. La decisión F fijó un servicio de auditoría y un
stack de observabilidad en un perfil opcional. Los accesos y las acciones
administrativas no tenían evento de dominio.

## Decisión
- **Fuente única:** Auditoría consume **todos** los eventos (`auditoria.eventos`,
  binding `#`). Se agrega el evento **`accion.auditada` v1** para lo que no tenía
  evento: inicios de sesión (éxito y fallo), reutilización de tokens, registro,
  creación, cambios, cambio de clave y eliminación de cuentas, moderación de
  denuncias y exportaciones regulatorias. Lo emiten identidad, atencion e
  indicadores por el outbox de Persistencia; cuando la acción tiene escritura
  (cuentas, moderación) va **en la misma transacción**. `caso.*` agrega el campo
  opcional `idActor` (compatible con v1).
- **Exportación auditada primero:** si no se puede auditar, no se exporta (503).
  La auditoría de inicios de sesión es de mejor esfuerzo para no bloquear a los
  usuarios (RNF-DC2).
- **Registro de solo inserción y encadenado:** `auditoria.registros` guarda en
  cada fila el SHA-256 de la anterior más su contenido canónico
  (`auditoria.huella`, calculada en PostgreSQL bajo bloqueo consultivo). Triggers
  prohíben UPDATE, DELETE y TRUNCATE; quien desactive el trigger y altere una
  fila rompe la cadena, y `GET /api/v1/auditoria/integridad` señala el primer
  registro alterado. Idempotente por `idEvento`.
- **Minimización:** solo identificadores seudónimos (UUID de cuenta). Nunca
  correos, nombres, claves, tokens, IP, coordenadas ni texto libre; un
  sanitizador elimina esos campos a cualquier profundidad.
- **Consulta:** por el gateway, solo `GET /api/v1/auditoria/**`, para regulador y
  administrador; la verificación de integridad, solo administrador. Se corrige
  así la regla de la fase 1 "auditoría nunca se enruta": se expone únicamente la
  lectura, nunca la escritura ni el webhook interno.
- **Alertas de seguridad (en Auditoría, siempre activas):** FUERZA_BRUTA_CUENTA
  (5 fallos sobre una cuenta en 10 min), FALLOS_ACCESO_MASIVOS (20 en 5 min),
  TOKEN_REUTILIZADO (inmediata) y EXPORTACIONES_INUSUALES (10 por cuenta en 1 h);
  quedan en el registro (categoría ALERTA) y en la métrica
  `servired_alertas_seguridad_total`.
- **Alertas operativas (perfil observabilidad):** Prometheus evalúa servicio
  caído, mensajes en las DLQ, colas acumuladas, outbox atascado o con fallos,
  errores 5xx > 5 %, p95 > 2 s (RNF de rendimiento) y señales de seguridad del
  gateway (401/403, 429) e identidad. Alertmanager las envía al webhook interno
  de Auditoría con un token de `.env`, y Auditoría las registra.
- **Trazas:** OpenTelemetry en los siete servicios (Micrometer Tracing en Java),
  exportadas por OTLP a Jaeger solo con `TRAZAS_ACTIVAS=true`. Los logs llevan
  `traceId` y `spanId`.

## Consecuencias
- Las ventanas del detector viven en memoria: un reinicio de Auditoría las
  reinicia (se acepta en el MVP; con varias réplicas se movería a Redis o SQL).
- La traza se corta en el outbox (publicación asíncrona); el `correlationId`
  sigue uniendo todo el flujo en logs y auditoría.
- Sin el perfil de observabilidad no hay alertas operativas por Prometheus, pero
  las de seguridad siguen activas en Auditoría.
- El registro crece sin límite; la retención y el archivado quedan como mejora.
  **Resuelto en [ADR-29](ADR-29-archivado-de-auditoria.md):** los meses viejos pasan a segmentos
  en el almacén sin romper la cadena.

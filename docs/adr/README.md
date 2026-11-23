# Registro de decisiones de arquitectura (ADR)

ADR-01 a ADR-04 están en el SAD de la Entrega 1 (`docs/Primera Entrega copy.docx`).
Desde ADR-05 se documentan las decisiones de la Entrega 2 (implementación con
JEE y .NET), incluidas las que corrigen contradicciones del SAD.

| ADR | Título | Estado |
|---|---|---|
| [ADR-05](ADR-05-pila-tecnologica.md) | Pila tecnológica: JEE (Spring) y .NET | Aceptada |
| [ADR-06](ADR-06-escritura-sincrona-y-outbox.md) | Escritura síncrona en Persistencia y outbox transaccional | Aceptada · corrige la vista de procesos |
| [ADR-07](ADR-07-estado-del-reporte-por-eventos.md) | El estado del reporte se actualiza con eventos de caso | Aceptada |
| [ADR-08](ADR-08-reglas-de-dominio.md) | Validación comunitaria, duración, enrutamiento y municipio | Aceptada |
| [ADR-09](ADR-09-denuncias-anonimas.md) | Anonimato de las denuncias | Aceptada |
| [ADR-10](ADR-10-topologia-rabbitmq.md) | Topología de RabbitMQ: reintentos y mensajes fallidos | Aceptada |
| [ADR-11](ADR-11-alcance-mvp.md) | Alcance del MVP: notificaciones, observabilidad y seguridad entre servicios | Aceptada |
| [ADR-12](ADR-12-identidad-y-control-de-acceso.md) | Identidad, tokens y control de acceso | Aceptada |
| [ADR-13](ADR-13-evidencias-y-almacenamiento.md) | Evidencias fotográficas y almacenamiento de objetos | Aceptada |
| [ADR-14](ADR-14-atencion-de-casos.md) | Atención de casos: enrutamiento, prioridad, ciclo de vida y acceso | Aceptada |
| [ADR-15](ADR-15-indicadores-y-datos-abiertos.md) | Indicadores, mapas y datos abiertos anonimizados | Aceptada |
| [ADR-16](ADR-16-auditoria-y-alertas.md) | Auditoría a prueba de manipulación y alertas | Aceptada |
| [ADR-17](ADR-17-portales-web.md) | Portales web: sesión en el navegador, proxy y anonimato | Aceptada |
| [ADR-18](ADR-18-app-movil-ciudadano.md) | Apps móviles del ciudadano (Android e iOS): sin conexión, sesión segura y mapas | Aceptada |
| [ADR-19](ADR-19-servidor-de-pruebas.md) | Servidor de pruebas fuera de local, sin costo | Aceptada |
| [ADR-20](ADR-20-replicas-y-circuit-breaker.md) | Réplicas y circuit breaker hacia Persistencia | Aceptada |
| [ADR-21](ADR-21-antiguedad-de-mensajes.md) | Antigüedad de los mensajes (RNF-R3): outbox y sonda en las colas | Aceptada |
| [ADR-22](ADR-22-abuso-con-varias-cuentas.md) | Abuso con varias cuentas: límites por cuenta y antigüedad para validar | Aceptada |
| [ADR-23](ADR-23-idempotencia-del-titular.md) | La idempotencia pertenece a quien hace la solicitud | Aceptada |
| [ADR-24](ADR-24-operacion-y-carga.md) | Operación de colas de fallidos, reconstrucción de Indicadores y prueba de carga 10× | Aceptada |
| [ADR-25](ADR-25-limpieza-de-objetos-huerfanos.md) | Limpieza de objetos huérfanos del almacén de evidencias | Aceptada |
| [ADR-26](ADR-26-revocacion-de-tokens-de-acceso.md) | Revocación de tokens de acceso por jti en el gateway | Aceptada |
| [ADR-27](ADR-27-municipio-por-poligonos-oficiales.md) | Municipio del reporte por polígonos oficiales (PostGIS) | Aceptada |
| [ADR-28](ADR-28-privacidad-rural-del-mapa.md) | Privacidad rural del mapa público: celdas y k distintos fuera de las cabeceras | Aceptada |
| [ADR-29](ADR-29-archivado-de-auditoria.md) | Archivado del registro de auditoría conservando la cadena de huellas | Aceptada |
| [ADR-30](ADR-30-refresco-en-cookie-httponly.md) | Token de refresco de los portales en una cookie HttpOnly | Aceptada |
| [ADR-31](ADR-31-anclas-externas-de-auditoria.md) | Anclas externas de la cadena de auditoría | Aceptada |
| [ADR-32](ADR-32-recalculo-del-municipio-de-reportes-viejos.md) | Recálculo del municipio de los reportes ubicados por centroide | Aceptada |
| [ADR-33](ADR-33-se-retira-la-auditoria.md) | Se retira la auditoría | Revertida (V18 y V19) |
| [ADR-34](ADR-34-mapa-base-openfreemap.md) | Mapa base vectorial de OpenFreeMap | Aceptada |
| [ADR-35](ADR-35-empresas-reales-y-zonas-de-servicio.md) | Empresas prestadoras reales y una sola dueña por zona de servicio | Aceptada |
| [ADR-36](ADR-36-borrado-del-rastro-de-las-empresas-de-ejemplo.md) | Borrado de todo lo relacionado con las empresas de ejemplo | Aceptada |

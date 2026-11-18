-- La idempotencia pertenece a quien hace la solicitud (ADR-23): se guarda una huella
-- (SHA-256) de la cuenta y el recurso de la primera solicitud. Si el mismo
-- X-Correlation-Id llega con otra huella u otro servicio, es otra solicitud: 409
-- CORRELACION_AJENA, en vez de devolver el resultado ajeno o descartarla en silencio.
-- Es una huella para no copiar aquí datos personales (p. ej. el correo del registro).
-- Las filas anteriores quedan sin huella y no se comparan.
ALTER TABLE infraestructura.solicitudes_procesadas ADD COLUMN huella TEXT;

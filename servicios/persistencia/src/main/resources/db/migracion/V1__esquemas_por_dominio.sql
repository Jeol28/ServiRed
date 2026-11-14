-- =====================================================================
-- V1 · Esquemas por dominio (ADR-02) e infraestructura de persistencia
-- Ningún otro servicio conoce estos esquemas: solo la fachada de
-- operaciones de negocio de este servicio.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS catalogo;     -- divipola, tipos de servicio, empresas operadoras
CREATE SCHEMA IF NOT EXISTS identidad;    -- cuentas, roles, sesiones
CREATE SCHEMA IF NOT EXISTS reportes;     -- reportes, restablecimientos, validaciones, denuncias
CREATE SCHEMA IF NOT EXISTS atencion;     -- casos y su historial
CREATE SCHEMA IF NOT EXISTS indicadores;  -- vista de lectura e indicadores precalculados
CREATE SCHEMA IF NOT EXISTS auditoria;    -- registros de auditoría (solo inserción)

-- ---------------------------------------------------------------------
-- Outbox transaccional (decisión B): el evento se guarda en la MISMA
-- transacción que la operación de negocio y un relay lo publica en
-- RabbitMQ con confirmación del broker. Así un evento solo existe si la
-- persistencia se confirmó, y no se pierde si el proceso cae.
-- ---------------------------------------------------------------------
CREATE TABLE infraestructura.outbox (
    id_evento       UUID        PRIMARY KEY,
    tipo            TEXT        NOT NULL,          -- p. ej. reporte.creado
    version         INTEGER     NOT NULL CHECK (version > 0),
    routing_key     TEXT        NOT NULL,          -- p. ej. reporte.creado.v1
    correlation_id  UUID        NOT NULL,
    servicio_origen TEXT        NOT NULL,
    sobre           JSONB       NOT NULL,          -- sobre completo del evento
    creado_en       TIMESTAMPTZ NOT NULL DEFAULT now(),
    publicado_en    TIMESTAMPTZ,
    intentos        INTEGER     NOT NULL DEFAULT 0,
    ultimo_error    TEXT
);

CREATE INDEX outbox_pendientes_idx
    ON infraestructura.outbox (creado_en)
    WHERE publicado_en IS NULL;

-- ---------------------------------------------------------------------
-- Idempotencia: el X-Correlation-Id es la clave. Una misma transacción
-- puede invocar varias operaciones, por eso la clave incluye la operación.
-- Un reintento devuelve la respuesta original sin volver a escribir.
-- ---------------------------------------------------------------------
CREATE TABLE infraestructura.solicitudes_procesadas (
    correlation_id  UUID        NOT NULL,
    operacion       TEXT        NOT NULL,          -- p. ej. guardarReporte
    servicio_origen TEXT        NOT NULL,
    respuesta       JSONB       NOT NULL,
    procesada_en    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (correlation_id, operacion)
);

-- =====================================================================
-- Fase 7: registro de auditoría (RF-12) de solo inserción y a prueba de
-- manipulación: cada registro guarda el hash del anterior (cadena de hashes),
-- así que alterar o borrar uno rompe la verificación de todos los siguientes.
-- =====================================================================

CREATE TABLE auditoria.registros (
    secuencia       BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Idempotencia: un evento se registra una sola vez.
    id_evento       UUID        NOT NULL UNIQUE,
    tipo            TEXT        NOT NULL,
    version         INTEGER     NOT NULL,
    origen          TEXT        NOT NULL,
    correlation_id  UUID,
    -- ACCESO, ADMINISTRACION, EXPORTACION, MODERACION, SEGURIDAD, CAMBIO_ESTADO, VALIDACION, ALERTA
    categoria       TEXT        NOT NULL,
    accion          TEXT        NOT NULL,
    resultado       TEXT        CHECK (resultado IN ('EXITO', 'FALLO')),
    -- Identificadores seudónimos (UUID de cuenta); nunca correos, nombres ni IP.
    actor_id        UUID,
    actor_rol       TEXT,
    recurso_tipo    TEXT,
    recurso_id      TEXT,
    detalle         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ocurrido_en     TIMESTAMPTZ NOT NULL,
    registrado_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
    hash_anterior   CHAR(64),
    hash            CHAR(64)    NOT NULL
);

CREATE INDEX idx_auditoria_categoria ON auditoria.registros (categoria, ocurrido_en DESC);
CREATE INDEX idx_auditoria_actor ON auditoria.registros (actor_id, ocurrido_en DESC) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_auditoria_recurso ON auditoria.registros (recurso_id, ocurrido_en DESC) WHERE recurso_id IS NOT NULL;

-- Huella de un registro: SHA-256 del hash anterior y de su contenido canónico
-- (detalle como JSONB::text, que PostgreSQL normaliza).
CREATE FUNCTION auditoria.huella(anterior TEXT, id_evento UUID, tipo TEXT, origen TEXT, categoria TEXT,
                                 accion TEXT, resultado TEXT, actor_id UUID, actor_rol TEXT, recurso_tipo TEXT,
                                 recurso_id TEXT, detalle JSONB, ocurrido_en TIMESTAMPTZ)
    RETURNS CHAR(64) LANGUAGE sql IMMUTABLE AS $$
    SELECT encode(sha256(convert_to(concat_ws('|',
        coalesce(anterior, ''), id_evento::text, tipo, origen, categoria, accion, coalesce(resultado, ''),
        coalesce(actor_id::text, ''), coalesce(actor_rol, ''), coalesce(recurso_tipo, ''), coalesce(recurso_id, ''),
        detalle::text, to_char(ocurrido_en AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')), 'UTF8')), 'hex')
$$;

-- Solo inserción: cualquier UPDATE, DELETE o TRUNCATE falla.
CREATE FUNCTION auditoria.prohibir_cambios() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'auditoria.registros es de solo inserción';
END;
$$;

CREATE TRIGGER registros_solo_insercion
    BEFORE UPDATE OR DELETE ON auditoria.registros
    FOR EACH ROW EXECUTE FUNCTION auditoria.prohibir_cambios();

CREATE TRIGGER registros_sin_truncate
    BEFORE TRUNCATE ON auditoria.registros
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.prohibir_cambios();

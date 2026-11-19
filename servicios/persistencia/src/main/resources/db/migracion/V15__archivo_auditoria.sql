-- =====================================================================
-- Archivado del registro de auditoría conservando la cadena de huellas (ADR-29).
--
-- Los registros viejos salen de la base en segmentos (archivos JSONL comprimidos en el
-- almacén de objetos, bajo auditoria/segmentos/) y no se destruyen. Cada segmento queda
-- descrito en auditoria.segmentos: su rango de secuencias, el hash_anterior de su primer
-- registro, el hash de su último registro y el SHA-256 del archivo. La cadena sigue
-- siendo verificable de punta a punta:
--   * cada segmento enlaza con el anterior (hash_anterior_inicial = hash_final previo);
--   * el primer registro que sigue en la base enlaza con el último segmento;
--   * cada archivo se puede descargar y recalcular registro por registro.
-- =====================================================================

CREATE TABLE auditoria.segmentos (
    id                     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    secuencia_desde        BIGINT      NOT NULL,
    secuencia_hasta        BIGINT      NOT NULL,
    registros              INTEGER     NOT NULL CHECK (registros > 0),
    -- null solo en el primer segmento (el primer registro de la cadena no tiene anterior)
    hash_anterior_inicial  CHAR(64),
    hash_final             CHAR(64)    NOT NULL,
    ocurrido_desde         TIMESTAMPTZ NOT NULL,
    ocurrido_hasta         TIMESTAMPTZ NOT NULL,
    clave_objeto           TEXT        NOT NULL UNIQUE,
    sha256_archivo         CHAR(64)    NOT NULL,
    bytes                  BIGINT      NOT NULL CHECK (bytes > 0),
    archivado_en           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (secuencia_hasta >= secuencia_desde),
    CHECK (registros <= secuencia_hasta - secuencia_desde + 1)
);
CREATE UNIQUE INDEX idx_segmentos_rango ON auditoria.segmentos (secuencia_desde);

-- El índice de segmentos es tan inmutable como el registro.
CREATE TRIGGER segmentos_solo_insercion
    BEFORE UPDATE OR DELETE ON auditoria.segmentos
    FOR EACH ROW EXECUTE FUNCTION auditoria.prohibir_cambios();
CREATE TRIGGER segmentos_sin_truncate
    BEFORE TRUNCATE ON auditoria.segmentos
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.prohibir_cambios();

-- Los registros siguen sin poder modificarse. Borrar solo se permite al archivar: con la
-- marca de la transacción de archivado y si un segmento ya registrado cubre la fila.
CREATE FUNCTION auditoria.proteger_registros() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND current_setting('servired.archivando_auditoria', true) = 'si'
       AND EXISTS (SELECT 1 FROM auditoria.segmentos s
                   WHERE OLD.secuencia BETWEEN s.secuencia_desde AND s.secuencia_hasta) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'auditoria.registros es de solo inserción (solo se archiva lo que ya cubre un segmento)';
END;
$$;

DROP TRIGGER registros_solo_insercion ON auditoria.registros;
CREATE TRIGGER registros_solo_insercion
    BEFORE UPDATE OR DELETE ON auditoria.registros
    FOR EACH ROW EXECUTE FUNCTION auditoria.proteger_registros();

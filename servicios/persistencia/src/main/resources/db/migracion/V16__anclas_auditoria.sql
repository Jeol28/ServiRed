-- =====================================================================
-- Anclas externas de la cadena de auditoría (ADR-31).
--
-- Cada día se fija el último hash de la cadena (con su secuencia) y se publica fuera del
-- sistema (un archivo en GitHub y la API pública). Si alguien alterara un segmento archivado
-- y recalculara todas las huellas siguientes, la cadena interna seguiría «íntegra», pero el
-- hash de esa secuencia ya no coincidiría con el publicado.
-- =====================================================================
CREATE TABLE auditoria.anclas (
    fecha          DATE        PRIMARY KEY,               -- día UTC
    secuencia      BIGINT      NOT NULL,                  -- último registro de la cadena ese día
    hash           CHAR(64)    NOT NULL,
    registros      BIGINT      NOT NULL,
    segmentos      INTEGER     NOT NULL,
    creada_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Dónde se publicó (null mientras no se haya podido publicar).
    publicada_en   TIMESTAMPTZ,
    destino        TEXT,
    referencia     TEXT
);

-- Lo anclado no cambia: solo se puede completar una vez dónde se publicó.
CREATE FUNCTION auditoria.proteger_anclas() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.publicada_en IS NULL
       AND NEW.fecha = OLD.fecha AND NEW.secuencia = OLD.secuencia AND NEW.hash = OLD.hash
       AND NEW.registros = OLD.registros AND NEW.segmentos = OLD.segmentos AND NEW.creada_en = OLD.creada_en THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'auditoria.anclas es inmutable (solo se registra una vez dónde se publicó)';
END;
$$;

CREATE TRIGGER anclas_inmutables
    BEFORE UPDATE OR DELETE ON auditoria.anclas
    FOR EACH ROW EXECUTE FUNCTION auditoria.proteger_anclas();
CREATE TRIGGER anclas_sin_truncate
    BEFORE TRUNCATE ON auditoria.anclas
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.prohibir_cambios();

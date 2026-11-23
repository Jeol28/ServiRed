-- =====================================================================
-- Vuelve la auditoría (ADR-33 revertida). V18 había borrado el esquema auditoria; aquí se
-- recrea igual que lo dejaron V9, V15 y V16, vacío: los registros, segmentos y anclas que
-- borró V18 no se recuperan. La cadena de huellas empieza de nuevo.
-- La protección de reportes.recalculo_municipio se queda en reportes.prohibir_cambios()
-- (V18); recalcular_municipios vuelve a dejar su resumen accion.auditada en el outbox (V17).
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS auditoria;

-- ---------------------------------------------------------------- V9: registro
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

-- ---------------------------------------------------------------- V15: segmentos archivados
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

-- ---------------------------------------------------------------- V16: anclas
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

-- ---------------------------------------------------------------- V17: recálculo con resumen
CREATE OR REPLACE FUNCTION reportes.recalcular_municipios(p_migracion TEXT)
RETURNS TABLE (reportes_cambiados BIGINT, casos_cambiados BIGINT, casos_reenrutados BIGINT)
LANGUAGE plpgsql AS $fn$
BEGIN
-- 1. Qué cambia (solo si los polígonos dan un municipio y es distinto).
    DROP TABLE IF EXISTS cambio;
    CREATE TEMP TABLE cambio ON COMMIT DROP AS
SELECT r.id AS id_reporte, r.codigo_municipio AS anterior, n.nuevo, r.tipo_servicio,
       c.id AS id_caso, c.estado, c.asignado_a, c.id_empresa AS empresa_anterior,
       (SELECT min(cb.id_empresa::text)::uuid FROM catalogo.coberturas cb
        WHERE cb.codigo_municipio = r.codigo_municipio AND cb.tipo_servicio = r.tipo_servicio) AS automatica_anterior,
       (SELECT min(cb.id_empresa::text)::uuid FROM catalogo.coberturas cb
        WHERE cb.codigo_municipio = n.nuevo AND cb.tipo_servicio = r.tipo_servicio) AS automatica_nueva
FROM reportes.reportes r
CROSS JOIN LATERAL (SELECT catalogo.municipio_en(r.latitud, r.longitud) AS nuevo) n
LEFT JOIN atencion.casos c ON c.id_reporte = r.id
WHERE n.nuevo IS NOT NULL AND n.nuevo <> r.codigo_municipio;

ALTER TABLE cambio ADD COLUMN reenrutar BOOLEAN;
UPDATE cambio SET reenrutar = id_caso IS NOT NULL AND estado = 'RECIBIDO' AND asignado_a IS NULL
    AND empresa_anterior IS NOT DISTINCT FROM automatica_anterior
    AND automatica_nueva IS DISTINCT FROM empresa_anterior;

-- 2. El registro de qué cambió.
INSERT INTO reportes.recalculo_municipio (id_reporte, municipio_anterior, municipio_nuevo, id_caso, estado_caso,
                                          empresa_anterior, empresa_nueva, caso_reenrutado, fuente, migracion)
SELECT id_reporte, anterior, nuevo, id_caso, estado, empresa_anterior,
       CASE WHEN reenrutar THEN automatica_nueva ELSE empresa_anterior END, reenrutar,
       'DANE MGN 2024 (polígonos municipales)', p_migracion
FROM cambio;

-- 3. Reportes.
UPDATE reportes.reportes r SET codigo_municipio = c.nuevo, actualizado_en = now()
FROM cambio c WHERE r.id = c.id_reporte;

-- 4. Casos: municipio, empresa si está intacto, versión e historia.
INSERT INTO atencion.historia_caso (id_caso, estado, fecha, detalle, autor)
SELECT c.id_caso, c.estado, now(),
       'Municipio corregido de ' || c.anterior || ' a ' || c.nuevo || ' con los límites oficiales del DANE'
           || CASE WHEN c.reenrutar THEN
                   CASE WHEN c.automatica_nueva IS NULL THEN '; sin empresa con cobertura: requiere enrutamiento manual'
                        ELSE '; reenrutado a la empresa con cobertura' END
              ELSE '' END,
       NULL
FROM cambio c WHERE c.id_caso IS NOT NULL;

UPDATE atencion.casos k
SET codigo_municipio = c.nuevo,
    id_empresa = CASE WHEN c.reenrutar THEN c.automatica_nueva ELSE k.id_empresa END,
    version = k.version + 1,
    actualizado_en = now()
FROM cambio c WHERE k.id = c.id_caso;

-- 5. Vista de Indicadores (y la empresa, si el caso se reenrutó).
UPDATE indicadores.vista_reportes v
SET codigo_municipio = c.nuevo,
    id_empresa = CASE WHEN c.reenrutar THEN c.automatica_nueva ELSE v.id_empresa END,
    actualizado_en = now()
FROM cambio c WHERE v.id_reporte = c.id_reporte;

-- 6. Resumen en la cadena de auditoría (si hubo cambios).
INSERT INTO infraestructura.outbox (id_evento, tipo, version, routing_key, correlation_id, servicio_origen, sobre)
SELECT e.id, 'accion.auditada', 1, 'accion.auditada.v1', e.correlacion, 'reportes',
       jsonb_build_object(
           'idEvento', e.id, 'tipo', 'accion.auditada', 'version', 1,
           'fechaOcurrencia', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           'correlationId', e.correlacion, 'origen', 'reportes',
           'datos', jsonb_build_object(
               'categoria', 'ADMINISTRACION', 'accion', 'MUNICIPIOS_RECALCULADOS', 'resultado', 'EXITO',
               'detalle', jsonb_build_object(
                   'migracion', p_migracion, 'fuente', 'DANE MGN 2024',
                   'reportes', (SELECT count(*) FROM cambio),
                   'casos', (SELECT count(*) FROM cambio WHERE id_caso IS NOT NULL),
                   'casosReenrutados', (SELECT count(*) FROM cambio WHERE reenrutar),
                   'registro', 'reportes.recalculo_municipio')))
FROM (SELECT gen_random_uuid() AS id, gen_random_uuid() AS correlacion) e
WHERE EXISTS (SELECT 1 FROM cambio);

    RETURN QUERY SELECT (SELECT count(*) FROM cambio), (SELECT count(*) FROM cambio WHERE id_caso IS NOT NULL),
                        (SELECT count(*) FROM cambio WHERE reenrutar);
END;
$fn$;

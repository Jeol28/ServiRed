-- =====================================================================
-- Recalcula el municipio de los reportes guardados antes de ADR-27, que se ubicaron por el
-- centroide DIVIPOLA más cercano, con los polígonos oficiales del DANE (ADR-32).
--
-- Deja registro de cada cambio en reportes.recalculo_municipio (solo inserción) y un resumen
-- en la cadena de auditoría (accion.auditada por el outbox).
--
-- Qué se actualiza de cada reporte cuyo municipio cambia:
--   * reportes.reportes y la vista de Indicadores (municipio);
--   * su caso: el municipio, una entrada en la historia y la versión (un operador con el caso
--     abierto en pantalla recibe un conflicto y lo recarga);
--   * la empresa del caso SOLO si el caso está intacto: RECIBIDO, sin asignar y con la empresa
--     que le dio el enrutamiento automático en el municipio anterior. Así no se le quita un
--     caso a nadie y no se deshace un enrutamiento manual. La nueva empresa sale de la misma
--     regla de Atención (entre las que cubren municipio y servicio, la de menor id; ninguna =
--     sin empresa, para enrutamiento manual).
-- Las denuncias anónimas no se tocan. Lo ya exportado no cambia.
-- =====================================================================

CREATE TABLE reportes.recalculo_municipio (
    id                  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_reporte          UUID        NOT NULL REFERENCES reportes.reportes (id),
    municipio_anterior  CHAR(5)     NOT NULL REFERENCES catalogo.municipios (codigo),
    municipio_nuevo     CHAR(5)     NOT NULL REFERENCES catalogo.municipios (codigo),
    id_caso             UUID,
    estado_caso         TEXT,
    empresa_anterior    UUID,
    empresa_nueva       UUID,
    caso_reenrutado     BOOLEAN     NOT NULL DEFAULT false,
    fuente              TEXT        NOT NULL,
    migracion           TEXT        NOT NULL,
    recalculado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id_reporte, migracion)
);
COMMENT ON TABLE reportes.recalculo_municipio IS
    'Reportes cuyo municipio se corrigió con los límites oficiales (ADR-32); solo inserción';

CREATE TRIGGER recalculo_municipio_solo_insercion
    BEFORE UPDATE OR DELETE ON reportes.recalculo_municipio
    FOR EACH ROW EXECUTE FUNCTION auditoria.prohibir_cambios();
CREATE TRIGGER recalculo_municipio_sin_truncate
    BEFORE TRUNCATE ON reportes.recalculo_municipio
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.prohibir_cambios();

-- La lógica queda en una función: la migración la llama una vez, las pruebas la ejercitan con
-- datos sembrados y sirve de nuevo si el DANE publica límites nuevos.
CREATE FUNCTION reportes.recalcular_municipios(p_migracion TEXT)
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

SELECT * FROM reportes.recalcular_municipios('V17');

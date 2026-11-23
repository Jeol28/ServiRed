-- =====================================================================
-- Las empresas de ejemplo se reemplazan por las reales (V21) y todo pasa a la dueña de su
-- zona (ADR-35): solo ella gestiona lo que cae allí.
--
--   * Casos (también los cerrados): la dueña de la zona de su ubicación (la del caso o, si no
--     la tiene, la del reporte). Si cambia de empresa se desasigna el operador, sube la versión
--     y queda una entrada en la historia.
--   * Moderaciones: la dueña de la zona de la denuncia.
--   * Vista de Indicadores: la empresa del caso.
--   * Cuentas de operador de las empresas de ejemplo: desactivadas, sin empresa y con sus
--     sesiones revocadas.
--   * Cada cambio queda en catalogo.reasignacion_empresas (solo inserción).
-- Al final se borran las empresas de ejemplo y la cobertura por municipio, que pasa a ser una
-- vista sobre la de zonas (la usa reportes.recalcular_municipios, V17).
-- =====================================================================

CREATE TABLE catalogo.reasignacion_empresas (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    objeto            TEXT        NOT NULL CHECK (objeto IN ('CASO', 'MODERACION', 'CUENTA')),
    id_objeto         UUID        NOT NULL,
    id_zona           TEXT,
    empresa_anterior  UUID,
    empresa_nueva     UUID,
    detalle           TEXT        NOT NULL,
    migracion         TEXT        NOT NULL,
    registrado_en     TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE catalogo.reasignacion_empresas IS
    'Qué cambió al pasar de las empresas de ejemplo a las reales (ADR-35); solo inserción';
CREATE TRIGGER reasignacion_empresas_solo_insercion
    BEFORE UPDATE OR DELETE ON catalogo.reasignacion_empresas
    FOR EACH ROW EXECUTE FUNCTION reportes.prohibir_cambios();
CREATE TRIGGER reasignacion_empresas_sin_truncate
    BEFORE TRUNCATE ON catalogo.reasignacion_empresas
    FOR EACH STATEMENT EXECUTE FUNCTION reportes.prohibir_cambios();

-- 1. Casos.
CREATE TEMP TABLE destino_caso ON COMMIT DROP AS
SELECT c.id, c.estado, c.id_empresa AS anterior, c.asignado_a, e.id_zona, e.id_empresa AS nueva
FROM atencion.casos c
LEFT JOIN reportes.reportes r ON r.id = c.id_reporte
CROSS JOIN LATERAL (
    SELECT * FROM catalogo.empresa_en(coalesce(c.latitud, r.latitud), coalesce(c.longitud, r.longitud),
                                      c.tipo_servicio)
    WHERE coalesce(c.latitud, r.latitud) IS NOT NULL
    UNION ALL
    SELECT * FROM catalogo.empresa_de_municipio(c.codigo_municipio, c.tipo_servicio)
    WHERE coalesce(c.latitud, r.latitud) IS NULL
) e;

INSERT INTO catalogo.reasignacion_empresas (objeto, id_objeto, id_zona, empresa_anterior, empresa_nueva, detalle, migracion)
SELECT 'CASO', id, id_zona, anterior, nueva,
       CASE WHEN nueva IS NULL THEN 'Sin empresa dueña en su zona: queda sin empresa'
            ELSE 'Pasa a la empresa dueña de su zona' END
           || CASE WHEN asignado_a IS NOT NULL THEN '; se desasigna el operador' ELSE '' END,
       'V22'
FROM destino_caso WHERE nueva IS DISTINCT FROM anterior;

INSERT INTO atencion.historia_caso (id_caso, estado, fecha, detalle, autor)
SELECT id, estado, now(),
       'Empresa reasignada a la dueña de su zona (' || coalesce(id_zona, 'sin zona') || ')'
           || CASE WHEN nueva IS NULL THEN ': ninguna empresa presta el servicio allí' ELSE '' END
           || CASE WHEN asignado_a IS NOT NULL THEN '; operador desasignado' ELSE '' END,
       NULL
FROM destino_caso WHERE nueva IS DISTINCT FROM anterior;

UPDATE atencion.casos k
SET id_empresa = d.nueva, asignado_a = NULL, version = k.version + 1, actualizado_en = now()
FROM destino_caso d WHERE k.id = d.id AND d.nueva IS DISTINCT FROM d.anterior;

UPDATE indicadores.vista_reportes v
SET id_empresa = k.id_empresa, actualizado_en = now()
FROM atencion.casos k
WHERE k.id_reporte = v.id_reporte AND v.id_empresa IS DISTINCT FROM k.id_empresa;

-- 2. Moderaciones de denuncias.
CREATE TEMP TABLE destino_moderacion ON COMMIT DROP AS
SELECT m.id_denuncia, m.id_empresa AS anterior, e.id_zona, e.id_empresa AS nueva
FROM atencion.moderaciones m
JOIN reportes.denuncias d ON d.id = m.id_denuncia
CROSS JOIN LATERAL catalogo.empresa_en(d.latitud, d.longitud, d.tipo_servicio) e;

INSERT INTO catalogo.reasignacion_empresas (objeto, id_objeto, id_zona, empresa_anterior, empresa_nueva, detalle, migracion)
SELECT 'MODERACION', id_denuncia, id_zona, anterior, nueva, 'Pasa a la empresa dueña de su zona', 'V22'
FROM destino_moderacion WHERE nueva IS DISTINCT FROM anterior;

UPDATE atencion.moderaciones m
SET id_empresa = d.nueva, version = m.version + 1
FROM destino_moderacion d WHERE m.id_denuncia = d.id_denuncia AND d.nueva IS DISTINCT FROM d.anterior;
-- Las moderaciones de denuncias que no se encontraran (no debería pasar) quedan sin empresa.
UPDATE atencion.moderaciones SET id_empresa = NULL, version = version + 1
WHERE id_empresa IN (SELECT id FROM catalogo.empresas WHERE id::text LIKE 'a0000000-0000-4000-8000-00000000000_');

-- 3. Operadores de las empresas de ejemplo: un operador inactivo puede quedar sin empresa.
ALTER TABLE identidad.cuentas DROP CONSTRAINT operador_con_empresa;
ALTER TABLE identidad.cuentas ADD CONSTRAINT operador_con_empresa
    CHECK ((rol = 'OPERADOR' OR id_empresa IS NULL) AND (rol <> 'OPERADOR' OR id_empresa IS NOT NULL OR NOT activa));

INSERT INTO catalogo.reasignacion_empresas (objeto, id_objeto, empresa_anterior, detalle, migracion)
SELECT 'CUENTA', id, id_empresa, 'Operador de una empresa de ejemplo: desactivado y sin empresa', 'V22'
FROM identidad.cuentas WHERE id_empresa IN (SELECT id FROM catalogo.empresas WHERE id::text LIKE 'a0000000-0000-4000-8000-00000000000_');

UPDATE identidad.sesiones SET revocada_en = now()
WHERE revocada_en IS NULL AND id_cuenta IN (
    SELECT id FROM identidad.cuentas
    WHERE id_empresa IN (SELECT id FROM catalogo.empresas WHERE id::text LIKE 'a0000000-0000-4000-8000-00000000000_'));

UPDATE identidad.cuentas SET activa = false, id_empresa = NULL
WHERE id_empresa IN (SELECT id FROM catalogo.empresas WHERE id::text LIKE 'a0000000-0000-4000-8000-00000000000_');

-- 4. Fuera las empresas de ejemplo y la cobertura por municipio.
DROP TABLE catalogo.coberturas;
DELETE FROM catalogo.empresas WHERE id::text LIKE 'a0000000-0000-4000-8000-00000000000_';

-- Vista de compatibilidad: la dueña de cada municipio y servicio (la de su cabecera o, si no
-- tiene, la de su resto rural). La usa reportes.recalcular_municipios (V17).
CREATE VIEW catalogo.coberturas AS
SELECT e.id_empresa, m.codigo AS codigo_municipio, t.codigo AS tipo_servicio
FROM catalogo.municipios m
CROSS JOIN catalogo.tipos_servicio t
CROSS JOIN LATERAL catalogo.empresa_de_municipio(m.codigo, t.codigo) e
WHERE e.id_empresa IS NOT NULL;

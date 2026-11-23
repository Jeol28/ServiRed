-- =====================================================================
-- Con V24 todo municipio tiene empresa dueña en los 5 servicios (ADR-37). Los casos y las
-- moderaciones que habían quedado sin empresa pasan a la dueña de su zona, como en V22: una
-- entrada en la historia, la versión sube y cada cambio queda en catalogo.reasignacion_empresas.
-- =====================================================================

CREATE TEMP TABLE destino_caso ON COMMIT DROP AS
SELECT c.id, c.estado, e.id_zona, e.id_empresa AS nueva
FROM atencion.casos c
LEFT JOIN reportes.reportes r ON r.id = c.id_reporte
CROSS JOIN LATERAL (
    SELECT * FROM catalogo.empresa_en(coalesce(c.latitud, r.latitud), coalesce(c.longitud, r.longitud),
                                      c.tipo_servicio)
    WHERE coalesce(c.latitud, r.latitud) IS NOT NULL
    UNION ALL
    SELECT * FROM catalogo.empresa_de_municipio(c.codigo_municipio, c.tipo_servicio)
    WHERE coalesce(c.latitud, r.latitud) IS NULL
) e
WHERE c.id_empresa IS NULL AND e.id_empresa IS NOT NULL;

INSERT INTO catalogo.reasignacion_empresas (objeto, id_objeto, id_zona, empresa_anterior, empresa_nueva, detalle, migracion)
SELECT 'CASO', id, id_zona, NULL, nueva, 'Sin empresa: pasa a la nueva dueña de su zona', 'V25' FROM destino_caso;

INSERT INTO atencion.historia_caso (id_caso, estado, fecha, detalle, autor)
SELECT id, estado, now(), 'Enrutado a la empresa dueña de su zona (' || id_zona || ')', NULL FROM destino_caso;

UPDATE atencion.casos k SET id_empresa = d.nueva, version = k.version + 1, actualizado_en = now()
FROM destino_caso d WHERE k.id = d.id;

UPDATE indicadores.vista_reportes v SET id_empresa = k.id_empresa, actualizado_en = now()
FROM atencion.casos k
WHERE k.id_reporte = v.id_reporte AND v.id_empresa IS DISTINCT FROM k.id_empresa;

CREATE TEMP TABLE destino_moderacion ON COMMIT DROP AS
SELECT m.id_denuncia, e.id_zona, e.id_empresa AS nueva
FROM atencion.moderaciones m
JOIN reportes.denuncias d ON d.id = m.id_denuncia
CROSS JOIN LATERAL catalogo.empresa_en(d.latitud, d.longitud, d.tipo_servicio) e
WHERE m.id_empresa IS NULL AND e.id_empresa IS NOT NULL;

INSERT INTO catalogo.reasignacion_empresas (objeto, id_objeto, id_zona, empresa_anterior, empresa_nueva, detalle, migracion)
SELECT 'MODERACION', id_denuncia, id_zona, NULL, nueva, 'Sin empresa: pasa a la nueva dueña de su zona', 'V25'
FROM destino_moderacion;

UPDATE atencion.moderaciones m SET id_empresa = d.nueva, version = m.version + 1
FROM destino_moderacion d WHERE m.id_denuncia = d.id_denuncia;

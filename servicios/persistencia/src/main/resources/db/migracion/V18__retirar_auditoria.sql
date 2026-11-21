-- =====================================================================
-- Se retira la auditoría (ADR-33): no estaba en el enunciado. Se borran el registro, los
-- segmentos archivados y las anclas (esquema auditoria completo). Irreversible. Los archivos
-- de segmentos que haya en el almacén (auditoria/segmentos/) y la rama de anclas en GitHub no
-- se tocan desde aquí.
-- =====================================================================

-- reportes.recalculo_municipio usaba auditoria.prohibir_cambios(): su protección pasa a su
-- propio esquema antes de borrar el de auditoría.
CREATE FUNCTION reportes.prohibir_cambios() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% es de solo inserción', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME;
END;
$$;

DROP TRIGGER recalculo_municipio_solo_insercion ON reportes.recalculo_municipio;
DROP TRIGGER recalculo_municipio_sin_truncate ON reportes.recalculo_municipio;
CREATE TRIGGER recalculo_municipio_solo_insercion
    BEFORE UPDATE OR DELETE ON reportes.recalculo_municipio
    FOR EACH ROW EXECUTE FUNCTION reportes.prohibir_cambios();
CREATE TRIGGER recalculo_municipio_sin_truncate
    BEFORE TRUNCATE ON reportes.recalculo_municipio
    FOR EACH STATEMENT EXECUTE FUNCTION reportes.prohibir_cambios();

-- El recálculo de municipios ya no publica un resumen de auditoría (accion.auditada).
CREATE OR REPLACE FUNCTION reportes.recalcular_municipios(p_migracion TEXT)
RETURNS TABLE (reportes_cambiados BIGINT, casos_cambiados BIGINT, casos_reenrutados BIGINT)
LANGUAGE plpgsql AS $fn$
BEGIN
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

    INSERT INTO reportes.recalculo_municipio (id_reporte, municipio_anterior, municipio_nuevo, id_caso, estado_caso,
                                              empresa_anterior, empresa_nueva, caso_reenrutado, fuente, migracion)
    SELECT id_reporte, anterior, nuevo, id_caso, estado, empresa_anterior,
           CASE WHEN reenrutar THEN automatica_nueva ELSE empresa_anterior END, reenrutar,
           'DANE MGN 2024 (polígonos municipales)', p_migracion
    FROM cambio;

    UPDATE reportes.reportes r SET codigo_municipio = c.nuevo, actualizado_en = now()
    FROM cambio c WHERE r.id = c.id_reporte;

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

    UPDATE indicadores.vista_reportes v
    SET codigo_municipio = c.nuevo,
        id_empresa = CASE WHEN c.reenrutar THEN c.automatica_nueva ELSE v.id_empresa END,
        actualizado_en = now()
    FROM cambio c WHERE v.id_reporte = c.id_reporte;

    RETURN QUERY SELECT (SELECT count(*) FROM cambio), (SELECT count(*) FROM cambio WHERE id_caso IS NOT NULL),
                        (SELECT count(*) FROM cambio WHERE reenrutar);
END;
$fn$;

-- Eventos de auditoría que aún no se hubieran publicado: ya no tienen consumidor.
DELETE FROM infraestructura.outbox WHERE tipo = 'accion.auditada' AND publicado_en IS NULL;

DROP SCHEMA auditoria CASCADE;

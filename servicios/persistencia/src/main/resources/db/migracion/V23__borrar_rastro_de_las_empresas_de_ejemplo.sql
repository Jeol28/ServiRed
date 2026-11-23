-- =====================================================================
-- Borra todo registro relacionado con las 5 empresas de ejemplo de V4, ya retiradas en V22
-- (ADR-36). Irreversible; lo decidió el usuario.
--
-- Qué se borra:
--   * Los operadores que fueron de esas empresas, con sus sesiones, las entradas de historia que
--     escribieron y las moderaciones que decidieron.
--   * Los casos y moderaciones que fueron de esas empresas, con su reporte o denuncia, fotos
--     (las filas; los archivos los limpia la tarea de huérfanos, ADR-25), votos, historia,
--     registro de recálculo de municipio y fila en la vista de Indicadores.
--   * Los eventos del outbox y las respuestas guardadas para idempotencia que nombran cualquiera
--     de esos registros o empresas.
--   * Las filas del registro de reasignación (V22) que nombran esas empresas o esos operadores.
--   * Los registros de auditoría que nombran esas empresas o cuyo actor fue uno de esos
--     operadores. La cadena se recalcula desde el primero borrado (reencadenamiento), y queda
--     constancia en auditoria.reencadenamientos para que la verificación de anclas distinga las
--     anclas ya publicadas que este reencadenamiento reemplazó.
-- No se deja registro de lo borrado (ni identificadores): solo los conteos.
-- La lógica queda en una función para poder probarla con datos sembrados.
-- =====================================================================

CREATE TABLE auditoria.reencadenamientos (
    id                 BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    desde_secuencia    BIGINT      NOT NULL,
    registros_borrados INTEGER     NOT NULL,
    motivo             TEXT        NOT NULL,
    migracion          TEXT        NOT NULL,
    realizado_en       TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE auditoria.reencadenamientos IS
    'Recálculos de la cadena de auditoría tras un borrado autorizado (ADR-36); solo inserción. Las '
    'anclas anteriores con secuencia >= desde_secuencia quedan reemplazadas';
CREATE TRIGGER reencadenamientos_solo_insercion
    BEFORE UPDATE OR DELETE ON auditoria.reencadenamientos
    FOR EACH ROW EXECUTE FUNCTION auditoria.prohibir_cambios();
CREATE TRIGGER reencadenamientos_sin_truncate
    BEFORE TRUNCATE ON auditoria.reencadenamientos
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.prohibir_cambios();

CREATE FUNCTION infraestructura.borrar_rastro_empresas_ejemplo(p_migracion TEXT)
RETURNS TABLE (objeto TEXT, borrados BIGINT)
LANGUAGE plpgsql AS $fn$
#variable_conflict use_column
DECLARE
    uuid_re CONSTANT TEXT := '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
    muestra_re CONSTANT TEXT := 'a0000000-0000-4000-8000-00000000000[1-5]';
    n BIGINT;
    desde BIGINT;
    previo TEXT;
    r RECORD;
BEGIN
    CREATE TEMP TABLE IF NOT EXISTS rastro_resumen (objeto TEXT, borrados BIGINT) ON COMMIT DROP;
    TRUNCATE rastro_resumen;

    -- ---------------------------------------------------------- qué está relacionado
    DROP TABLE IF EXISTS pg_temp.muestra, pg_temp.cuentas_x, pg_temp.casos_x, pg_temp.reportes_x, pg_temp.denuncias_x,
        pg_temp.ids_x;
    CREATE TEMP TABLE muestra ON COMMIT DROP AS
        SELECT ('a0000000-0000-4000-8000-00000000000' || i)::uuid AS id FROM generate_series(1, 5) i;
    CREATE TEMP TABLE cuentas_x ON COMMIT DROP AS
        SELECT id_objeto AS id FROM catalogo.reasignacion_empresas WHERE objeto = 'CUENTA'
        UNION SELECT id FROM identidad.cuentas WHERE id_empresa IN (SELECT id FROM muestra);
    CREATE TEMP TABLE casos_x ON COMMIT DROP AS
        SELECT id_objeto AS id FROM catalogo.reasignacion_empresas
        WHERE objeto = 'CASO' AND empresa_anterior IN (SELECT id FROM muestra)
        UNION SELECT id FROM atencion.casos
        WHERE id_empresa IN (SELECT id FROM muestra) OR asignado_a IN (SELECT id FROM cuentas_x);
    CREATE TEMP TABLE reportes_x ON COMMIT DROP AS
        SELECT id_reporte AS id FROM atencion.casos WHERE id IN (SELECT id FROM casos_x);
    CREATE TEMP TABLE denuncias_x ON COMMIT DROP AS
        SELECT id_objeto AS id FROM catalogo.reasignacion_empresas
        WHERE objeto = 'MODERACION' AND empresa_anterior IN (SELECT id FROM muestra)
        UNION SELECT id_denuncia FROM atencion.moderaciones
        WHERE id_empresa IN (SELECT id FROM muestra) OR moderada_por IN (SELECT id FROM cuentas_x);
    -- Todo identificador relacionado (también fotos): para buscarlos dentro de JSON.
    CREATE TEMP TABLE ids_x (id TEXT PRIMARY KEY) ON COMMIT DROP;
    INSERT INTO ids_x
        SELECT id::text FROM muestra UNION SELECT id::text FROM cuentas_x UNION SELECT id::text FROM casos_x
        UNION SELECT id::text FROM reportes_x UNION SELECT id::text FROM denuncias_x
        UNION SELECT e.id::text FROM reportes.evidencias e
              WHERE e.id_reporte IN (SELECT id FROM reportes_x) OR e.id_denuncia IN (SELECT id FROM denuncias_x)
    ON CONFLICT DO NOTHING;

    -- ---------------------------------------------------------- eventos e idempotencia
    DELETE FROM infraestructura.outbox o
    WHERE EXISTS (SELECT 1 FROM regexp_matches(o.sobre::text, uuid_re, 'g') m JOIN ids_x x ON x.id = m[1]);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('eventos del outbox', n);

    DELETE FROM infraestructura.solicitudes_procesadas s
    WHERE EXISTS (SELECT 1 FROM regexp_matches(s.respuesta::text, uuid_re, 'g') m JOIN ids_x x ON x.id = m[1]);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('respuestas de idempotencia', n);

    -- ---------------------------------------------------------- auditoría y reencadenamiento
    SELECT min(secuencia) INTO desde FROM auditoria.registros a
    WHERE a.detalle::text ~ muestra_re OR a.recurso_id ~ muestra_re
       OR a.actor_id IN (SELECT id FROM cuentas_x);
    IF desde IS NOT NULL THEN
        ALTER TABLE auditoria.registros DISABLE TRIGGER registros_solo_insercion;
        DELETE FROM auditoria.registros a
        WHERE a.detalle::text ~ muestra_re OR a.recurso_id ~ muestra_re
           OR a.actor_id IN (SELECT id FROM cuentas_x);
        GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('registros de auditoría', n);
        -- El primero que queda después del hueco enlaza con el anterior en la base o, si no hay,
        -- con el último segmento archivado (o con nada, si la cadena empieza ahí).
        SELECT hash INTO previo FROM auditoria.registros WHERE secuencia < desde ORDER BY secuencia DESC LIMIT 1;
        IF previo IS NULL THEN
            SELECT hash_final INTO previo FROM auditoria.segmentos ORDER BY secuencia_hasta DESC LIMIT 1;
        END IF;
        FOR r IN SELECT * FROM auditoria.registros WHERE secuencia > desde ORDER BY secuencia LOOP
            UPDATE auditoria.registros
            SET hash_anterior = previo,
                hash = auditoria.huella(previo, r.id_evento, r.tipo, r.origen, r.categoria, r.accion, r.resultado,
                                        r.actor_id, r.actor_rol, r.recurso_tipo, r.recurso_id, r.detalle, r.ocurrido_en)
            WHERE secuencia = r.secuencia
            RETURNING hash INTO previo;
        END LOOP;
        ALTER TABLE auditoria.registros ENABLE TRIGGER registros_solo_insercion;
        INSERT INTO auditoria.reencadenamientos (desde_secuencia, registros_borrados, motivo, migracion)
        VALUES (desde, n, 'Borrado de todo lo relacionado con las empresas de ejemplo (ADR-36)', p_migracion);
    END IF;

    -- ---------------------------------------------------------- registro de reasignación (V22)
    ALTER TABLE catalogo.reasignacion_empresas DISABLE TRIGGER reasignacion_empresas_solo_insercion;
    DELETE FROM catalogo.reasignacion_empresas
    WHERE objeto = 'CUENTA' OR empresa_anterior IN (SELECT id FROM muestra) OR empresa_nueva IN (SELECT id FROM muestra);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('filas del registro de reasignación', n);
    ALTER TABLE catalogo.reasignacion_empresas ENABLE TRIGGER reasignacion_empresas_solo_insercion;

    -- ---------------------------------------------------------- casos y reportes
    ALTER TABLE reportes.recalculo_municipio DISABLE TRIGGER recalculo_municipio_solo_insercion;
    DELETE FROM reportes.recalculo_municipio
    WHERE id_reporte IN (SELECT id FROM reportes_x) OR id_caso IN (SELECT id FROM casos_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('filas del recálculo de municipio', n);
    ALTER TABLE reportes.recalculo_municipio ENABLE TRIGGER recalculo_municipio_solo_insercion;

    DELETE FROM atencion.historia_caso
    WHERE id_caso IN (SELECT id FROM casos_x) OR autor IN (SELECT id FROM cuentas_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('entradas de historia de casos', n);
    DELETE FROM atencion.casos WHERE id IN (SELECT id FROM casos_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('casos', n);
    DELETE FROM indicadores.vista_reportes WHERE id_reporte IN (SELECT id FROM reportes_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('filas de la vista de Indicadores', n);
    DELETE FROM reportes.evidencias
    WHERE id_reporte IN (SELECT id FROM reportes_x) OR id_denuncia IN (SELECT id FROM denuncias_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('fotos (filas)', n);
    DELETE FROM reportes.validaciones WHERE id_reporte IN (SELECT id FROM reportes_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('votos de validación', n);
    DELETE FROM reportes.historia_reporte WHERE id_reporte IN (SELECT id FROM reportes_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('entradas de historia de reportes', n);
    DELETE FROM reportes.reportes WHERE id IN (SELECT id FROM reportes_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('reportes', n);

    -- ---------------------------------------------------------- denuncias
    DELETE FROM atencion.moderaciones WHERE id_denuncia IN (SELECT id FROM denuncias_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('moderaciones', n);
    DELETE FROM reportes.denuncias WHERE id IN (SELECT id FROM denuncias_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('denuncias', n);

    -- ---------------------------------------------------------- operadores
    DELETE FROM identidad.sesiones WHERE id_cuenta IN (SELECT id FROM cuentas_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('sesiones', n);
    DELETE FROM identidad.cuentas WHERE id IN (SELECT id FROM cuentas_x);
    GET DIAGNOSTICS n = ROW_COUNT; INSERT INTO rastro_resumen VALUES ('cuentas de operador', n);

    DELETE FROM catalogo.empresas WHERE id IN (SELECT id FROM muestra);

    RETURN QUERY SELECT * FROM rastro_resumen;
END;
$fn$;

COMMENT ON FUNCTION infraestructura.borrar_rastro_empresas_ejemplo(TEXT) IS
    'Borra todo lo relacionado con las empresas de ejemplo de V4 (ADR-36); solo toca esas 5 empresas';

SELECT * FROM infraestructura.borrar_rastro_empresas_ejemplo('V23');

-- Sin operadores inactivos sin empresa, vuelve la restricción original: operador si y solo si
-- tiene empresa.
ALTER TABLE identidad.cuentas DROP CONSTRAINT operador_con_empresa;
ALTER TABLE identidad.cuentas ADD CONSTRAINT operador_con_empresa CHECK ((rol = 'OPERADOR') = (id_empresa IS NOT NULL));

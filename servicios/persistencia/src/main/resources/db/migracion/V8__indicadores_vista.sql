-- =====================================================================
-- Fase 6: vista de lectura de Indicadores (RF-8 a RF-10).
-- Una fila por reporte, proyectada desde los eventos. No contiene el
-- identificador del ciudadano ni la descripción (minimización, RNF-DAT2).
-- Las columnas admiten null porque los eventos pueden llegar en desorden:
-- la fila se completa a medida que llegan (upsert idempotente).
-- =====================================================================

CREATE TABLE indicadores.vista_reportes (
    id_reporte             UUID             PRIMARY KEY,
    -- reporte.creado
    tipo_servicio          TEXT,
    codigo_municipio       CHAR(5) REFERENCES catalogo.municipios (codigo),
    latitud                DOUBLE PRECISION,
    longitud               DOUBLE PRECISION,
    magnitud               TEXT,
    inicio_estimado        TIMESTAMPTZ,
    fecha_registro         TIMESTAMPTZ,
    -- restablecimiento.registrado
    fecha_restablecimiento TIMESTAMPTZ,
    duracion_minutos       INTEGER,
    -- validacion.registrada (objeto CORTE); se conserva la más reciente
    validado               BOOLEAN NOT NULL DEFAULT false,
    confirmaciones_netas   INTEGER NOT NULL DEFAULT 0,
    validacion_en          TIMESTAMPTZ,
    -- caso.actualizado / caso.cerrado; se conserva el más reciente
    estado_caso            TEXT,
    prioridad              TEXT,
    id_empresa             UUID,
    caso_en                TIMESTAMPTZ,
    resuelto_en            TIMESTAMPTZ,
    cerrado_en             TIMESTAMPTZ,
    actualizado_en         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vista_periodo ON indicadores.vista_reportes (fecha_registro);
CREATE INDEX idx_vista_territorio ON indicadores.vista_reportes (codigo_municipio, tipo_servicio, fecha_registro);
CREATE INDEX idx_vista_empresa ON indicadores.vista_reportes (id_empresa, fecha_registro);

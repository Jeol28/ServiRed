-- =====================================================================
-- Fase 4: restablecimiento (RF-3), validación comunitaria (RF-4),
-- evidencias (RF-1) y denuncias anónimas (RF-5).
-- =====================================================================

-- ---------- Restablecimiento y control de concurrencia de validaciones ----------
ALTER TABLE reportes.reportes
    ADD COLUMN fecha_restablecimiento      TIMESTAMPTZ,
    ADD COLUMN duracion_minutos            INTEGER CHECK (duracion_minutos >= 0),
    ADD COLUMN comentario_restablecimiento TEXT CHECK (char_length(comentario_restablecimiento) <= 500),
    -- Versión optimista: Reportes calcula las confirmaciones netas y solo se
    -- guarda el voto si nadie votó entre su lectura y su escritura.
    ADD COLUMN version_validaciones        INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT restablecimiento_completo
        CHECK ((fecha_restablecimiento IS NULL) = (duracion_minutos IS NULL)),
    ADD CONSTRAINT restablecimiento_posterior
        CHECK (fecha_restablecimiento IS NULL OR fecha_restablecimiento > inicio_estimado);

-- ---------- Validación comunitaria (un voto por ciudadano y objeto) ----------
-- No se guarda la ubicación del validador: solo se usa para comprobar la
-- distancia en el momento del voto (minimización de datos).
CREATE TABLE reportes.validaciones (
    id_reporte    UUID        NOT NULL REFERENCES reportes.reportes (id),
    objeto        TEXT        NOT NULL CHECK (objeto IN ('CORTE', 'RESTABLECIMIENTO')),
    id_ciudadano  UUID        NOT NULL,
    sentido       TEXT        NOT NULL CHECK (sentido IN ('CONFIRMA', 'CONTROVIERTE')),
    fecha         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id_reporte, objeto, id_ciudadano)
);

-- ---------- Denuncias anónimas ----------
-- Sin ningún identificador del denunciante (RNF-SEG4): ni cuenta, ni IP, ni
-- dispositivo. El correlationId de la transacción lo genera el gateway.
CREATE TABLE reportes.denuncias (
    id                UUID             PRIMARY KEY,
    tipo_servicio     TEXT             NOT NULL REFERENCES catalogo.tipos_servicio (codigo),
    latitud           DOUBLE PRECISION NOT NULL CHECK (latitud BETWEEN -4.3 AND 13.5),
    longitud          DOUBLE PRECISION NOT NULL CHECK (longitud BETWEEN -82 AND -66.8),
    codigo_municipio  CHAR(5)          NOT NULL REFERENCES catalogo.municipios (codigo),
    descripcion       TEXT             NOT NULL CHECK (char_length(descripcion) BETWEEN 10 AND 2000),
    estado            TEXT             NOT NULL DEFAULT 'PENDIENTE_VALIDACION'
        CHECK (estado IN ('PENDIENTE_VALIDACION', 'VALIDADA', 'DESCARTADA')),
    fecha_recepcion   TIMESTAMPTZ      NOT NULL
);
CREATE INDEX idx_denuncias_estado ON reportes.denuncias (estado, fecha_recepcion);

-- ---------- Evidencias (el archivo vive en Storage; aquí solo la referencia) ----------
CREATE TABLE reportes.evidencias (
    id             UUID        PRIMARY KEY,
    id_reporte     UUID        REFERENCES reportes.reportes (id),
    id_denuncia    UUID        REFERENCES reportes.denuncias (id),
    clave_objeto   TEXT        NOT NULL UNIQUE,
    tipo_contenido TEXT        NOT NULL CHECK (tipo_contenido IN ('image/jpeg', 'image/png')),
    tamano_bytes   INTEGER     NOT NULL CHECK (tamano_bytes > 0),
    sha256         CHAR(64)    NOT NULL,
    creada_en      TIMESTAMPTZ NOT NULL,
    CONSTRAINT un_solo_propietario CHECK ((id_reporte IS NULL) <> (id_denuncia IS NULL))
);
CREATE INDEX idx_evidencias_reporte ON reportes.evidencias (id_reporte) WHERE id_reporte IS NOT NULL;

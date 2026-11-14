-- =====================================================================
-- Fase 2: catálogo territorial y de servicios, reportes ciudadanos y
-- casos de atención. Cada dominio en su esquema; no hay llaves foráneas
-- entre reportes y atencion (se relacionan por eventos, ADR-07).
-- =====================================================================

-- ---------- Catálogo ----------
CREATE TABLE catalogo.departamentos (
    codigo  CHAR(2) PRIMARY KEY,
    nombre  TEXT    NOT NULL
);

CREATE TABLE catalogo.municipios (
    codigo               CHAR(5)          PRIMARY KEY,
    codigo_departamento  CHAR(2)          NOT NULL REFERENCES catalogo.departamentos (codigo),
    nombre               TEXT             NOT NULL,
    tipo                 TEXT             NOT NULL
        CHECK (tipo IN ('MUNICIPIO', 'ISLA', 'AREA_NO_MUNICIPALIZADA')),
    -- Centroide aproximado (WGS84) para resolver el municipio más cercano.
    latitud              DOUBLE PRECISION NOT NULL CHECK (latitud BETWEEN -4.3 AND 13.5),
    longitud             DOUBLE PRECISION NOT NULL CHECK (longitud BETWEEN -82 AND -66.8)
);
CREATE INDEX idx_municipios_departamento ON catalogo.municipios (codigo_departamento);

CREATE TABLE catalogo.tipos_servicio (
    codigo  TEXT PRIMARY KEY,
    nombre  TEXT NOT NULL
);

-- Empresas prestadoras y su cobertura (empresa × municipio × servicio), base
-- del enrutamiento de casos (ADR-08, fase 5).
CREATE TABLE catalogo.empresas (
    id      UUID PRIMARY KEY,
    nit     TEXT NOT NULL UNIQUE,
    nombre  TEXT NOT NULL
);

CREATE TABLE catalogo.coberturas (
    id_empresa        UUID    NOT NULL REFERENCES catalogo.empresas (id),
    codigo_municipio  CHAR(5) NOT NULL REFERENCES catalogo.municipios (codigo),
    tipo_servicio     TEXT    NOT NULL REFERENCES catalogo.tipos_servicio (codigo),
    PRIMARY KEY (codigo_municipio, tipo_servicio, id_empresa)
);

-- ---------- Reportes ciudadanos (RF-1, RF-2) ----------
CREATE TABLE reportes.reportes (
    id                UUID             PRIMARY KEY,
    id_ciudadano      UUID             NOT NULL,
    tipo_servicio     TEXT             NOT NULL REFERENCES catalogo.tipos_servicio (codigo),
    latitud           DOUBLE PRECISION NOT NULL CHECK (latitud BETWEEN -4.3 AND 13.5),
    longitud          DOUBLE PRECISION NOT NULL CHECK (longitud BETWEEN -82 AND -66.8),
    codigo_municipio  CHAR(5)          NOT NULL REFERENCES catalogo.municipios (codigo),
    inicio_estimado   TIMESTAMPTZ      NOT NULL,
    magnitud          TEXT             NOT NULL
        CHECK (magnitud IN ('VIVIENDA', 'CUADRA', 'BARRIO', 'SECTOR')),
    descripcion       TEXT             CHECK (char_length(descripcion) <= 1000),
    estado            TEXT             NOT NULL
        CHECK (estado IN ('RECIBIDO', 'EN_ATENCION', 'RESTABLECIDO', 'CERRADO')),
    fecha_registro    TIMESTAMPTZ      NOT NULL,
    actualizado_en    TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX idx_reportes_ciudadano ON reportes.reportes (id_ciudadano, fecha_registro DESC);
CREATE INDEX idx_reportes_municipio_servicio ON reportes.reportes (codigo_municipio, tipo_servicio, fecha_registro DESC);

CREATE TABLE reportes.historia_reporte (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_reporte  UUID        NOT NULL REFERENCES reportes.reportes (id),
    estado      TEXT        NOT NULL,
    fecha       TIMESTAMPTZ NOT NULL,
    detalle     TEXT
);
CREATE INDEX idx_historia_reporte ON reportes.historia_reporte (id_reporte, fecha);

-- ---------- Casos de atención (RF-6) ----------
-- No guarda el identificador del ciudadano: Atención no lo necesita (mínimo privilegio).
CREATE TABLE atencion.casos (
    id                UUID        PRIMARY KEY,
    id_reporte        UUID        NOT NULL UNIQUE,
    tipo_servicio     TEXT        NOT NULL REFERENCES catalogo.tipos_servicio (codigo),
    codigo_municipio  CHAR(5)     NOT NULL REFERENCES catalogo.municipios (codigo),
    magnitud          TEXT        NOT NULL,
    estado            TEXT        NOT NULL
        CHECK (estado IN ('RECIBIDO', 'EN_ATENCION', 'RESUELTO', 'CERRADO')),
    abierto_en        TIMESTAMPTZ NOT NULL,
    actualizado_en    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_casos_estado ON atencion.casos (estado, abierto_en);

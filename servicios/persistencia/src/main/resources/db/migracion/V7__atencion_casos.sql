-- =====================================================================
-- Fase 5: atención de casos (RF-6, RF-7) y moderación de denuncias (RF-5).
-- =====================================================================

-- ---------- Casos: enrutamiento, prioridad, asignación y datos operativos ----------
ALTER TABLE atencion.casos
    -- Empresa responsable según su cobertura (null = sin cobertura; la enruta un administrador).
    ADD COLUMN id_empresa        UUID REFERENCES catalogo.empresas (id),
    ADD COLUMN prioridad         TEXT NOT NULL DEFAULT 'MEDIA'
        CHECK (prioridad IN ('BAJA', 'MEDIA', 'ALTA', 'CRITICA')),
    -- Si un operador fijó la prioridad, el cálculo automático ya no la cambia.
    ADD COLUMN prioridad_manual  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN corte_validado    BOOLEAN NOT NULL DEFAULT false,
    -- Copia operativa del reporte (sin ningún dato del ciudadano).
    ADD COLUMN latitud           DOUBLE PRECISION,
    ADD COLUMN longitud          DOUBLE PRECISION,
    ADD COLUMN descripcion       TEXT,
    ADD COLUMN inicio_estimado   TIMESTAMPTZ,
    -- Operador (cuenta de personal) asignado.
    ADD COLUMN asignado_a        UUID,
    ADD COLUMN resolucion        TEXT CHECK (char_length(resolucion) <= 1000),
    ADD COLUMN resuelto_en       TIMESTAMPTZ,
    ADD COLUMN cerrado_en        TIMESTAMPTZ,
    -- Concurrencia optimista: dos operadores no pisan sus cambios.
    ADD COLUMN version           INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_casos_bandeja ON atencion.casos (id_empresa, estado, prioridad, abierto_en);

CREATE TABLE atencion.historia_caso (
    id       BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_caso  UUID        NOT NULL REFERENCES atencion.casos (id),
    estado   TEXT        NOT NULL,
    fecha    TIMESTAMPTZ NOT NULL,
    detalle  TEXT        NOT NULL,
    -- Cuenta del personal que actuó; null cuando fue el sistema.
    autor    UUID
);
CREATE INDEX idx_historia_caso ON atencion.historia_caso (id_caso, id);

-- ---------- Moderación de denuncias anónimas ----------
-- El contenido de la denuncia vive en reportes.denuncias; aquí solo la
-- decisión del moderador (no hay llave foránea entre dominios).
CREATE TABLE atencion.moderaciones (
    id_denuncia   UUID        PRIMARY KEY,
    id_empresa    UUID        REFERENCES catalogo.empresas (id),
    estado        TEXT        NOT NULL DEFAULT 'PENDIENTE'
        CHECK (estado IN ('PENDIENTE', 'VALIDADA', 'DESCARTADA')),
    nota          TEXT        CHECK (char_length(nota) <= 1000),
    moderada_por  UUID,
    moderada_en   TIMESTAMPTZ,
    recibida_en   TIMESTAMPTZ NOT NULL,
    version       INTEGER     NOT NULL DEFAULT 0
);
CREATE INDEX idx_moderaciones_bandeja ON atencion.moderaciones (id_empresa, estado, recibida_en);

-- ---------- Restablecimiento cuando el operador ya resolvió ----------
-- Si el caso se resolvió primero (reporte RESTABLECIDO sin fecha), el
-- ciudadano todavía puede registrar la hora real del restablecimiento.

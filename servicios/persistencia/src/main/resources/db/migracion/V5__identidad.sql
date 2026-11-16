-- =====================================================================
-- Fase 3: cuentas y sesiones (RF-11). Solo el servicio Identidad puede
-- invocar las operaciones que usan estas tablas.
-- =====================================================================

CREATE TABLE identidad.cuentas (
    id                      UUID        PRIMARY KEY,
    -- Normalizado (minúsculas, sin espacios) por Identidad. Dato personal:
    -- nunca se registra en logs ni viaja en eventos.
    correo                  TEXT        NOT NULL UNIQUE,
    -- Hash bcrypt; la clave en claro nunca llega a Persistencia.
    hash_clave              TEXT        NOT NULL,
    rol                     TEXT        NOT NULL
        CHECK (rol IN ('CIUDADANO', 'OPERADOR', 'REGULADOR', 'ADMINISTRADOR')),
    -- Empresa prestadora a la que pertenece un operador (segregación, RNF-SEG2).
    id_empresa              UUID        REFERENCES catalogo.empresas (id),
    nombre                  TEXT        CHECK (char_length(nombre) <= 120),
    activa                  BOOLEAN     NOT NULL DEFAULT true,
    -- Autorización de tratamiento de datos personales (Ley 1581 de 2012).
    consentimiento_datos_en TIMESTAMPTZ,
    creada_en               TIMESTAMPTZ NOT NULL,
    actualizada_en          TIMESTAMPTZ NOT NULL DEFAULT now(),
    eliminada_en            TIMESTAMPTZ,
    CONSTRAINT operador_con_empresa CHECK ((rol = 'OPERADOR') = (id_empresa IS NOT NULL))
);
CREATE INDEX idx_cuentas_rol ON identidad.cuentas (rol, creada_en);

-- Sesiones de renovación. Solo se guarda el SHA-256 del token de refresco.
CREATE TABLE identidad.sesiones (
    id               UUID        PRIMARY KEY,
    id_cuenta        UUID        NOT NULL REFERENCES identidad.cuentas (id),
    hash_refresco    TEXT        NOT NULL UNIQUE,
    creada_en        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_en        TIMESTAMPTZ NOT NULL,
    revocada_en      TIMESTAMPTZ,
    reemplazada_por  UUID
);
CREATE INDEX idx_sesiones_cuenta_activas ON identidad.sesiones (id_cuenta) WHERE revocada_en IS NULL;

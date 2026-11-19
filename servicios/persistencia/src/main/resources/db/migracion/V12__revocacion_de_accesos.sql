-- Revocación de tokens de acceso por jti (ADR-26).
-- Cada sesión (token de refresco) emite un token de acceso: su jti y su expiración se
-- guardan con la sesión. Al revocar sesiones (desactivar, suprimir o cambiar la clave de la
-- cuenta, reutilizar un refresco, cerrar sesión) se marcan también sus accesos aún
-- vigentes, y el gateway los rechaza. Una rotación normal no revoca el acceso anterior.
ALTER TABLE identidad.sesiones
    ADD COLUMN jti_acceso         UUID,
    ADD COLUMN acceso_expira_en   TIMESTAMPTZ,
    ADD COLUMN acceso_revocado_en TIMESTAMPTZ;

CREATE INDEX idx_sesiones_accesos_revocados ON identidad.sesiones (acceso_revocado_en)
    WHERE acceso_revocado_en IS NOT NULL;

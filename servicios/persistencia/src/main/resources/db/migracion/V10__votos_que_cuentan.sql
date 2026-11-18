-- Antigüedad mínima para validar (ADR-22): el voto de una cuenta recién creada se guarda
-- (el ciudadano ya votó y no puede repetir), pero no suma a los conteos ni al umbral.
-- Así crear cuentas en masa no sirve para validar un corte al instante.
-- Los votos anteriores siguen contando.
ALTER TABLE reportes.validaciones ADD COLUMN cuenta BOOLEAN NOT NULL DEFAULT true;

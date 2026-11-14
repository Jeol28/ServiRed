-- Tipos de servicio público domiciliario y empresas prestadoras de EJEMPLO.
-- Los nombres y NIT de las empresas son ficticios (datos de prueba académicos).

INSERT INTO catalogo.tipos_servicio (codigo, nombre) VALUES
  ('ENERGIA',        'Energía eléctrica'),
  ('GAS',            'Gas natural o licuado'),
  ('ACUEDUCTO',      'Acueducto (agua potable)'),
  ('ALCANTARILLADO', 'Alcantarillado'),
  ('ASEO',           'Aseo (recolección de residuos)');

INSERT INTO catalogo.empresas (id, nit, nombre) VALUES
  ('a0000000-0000-4000-8000-000000000001', '900000001-1', 'Energía Andina S.A. E.S.P. (ejemplo)'),
  ('a0000000-0000-4000-8000-000000000002', '900000002-2', 'Aguas de la Sabana S.A. E.S.P. (ejemplo)'),
  ('a0000000-0000-4000-8000-000000000003', '900000003-3', 'Gases del Valle S.A. E.S.P. (ejemplo)'),
  ('a0000000-0000-4000-8000-000000000004', '900000004-4', 'Servicios Integrales del Caribe S.A. E.S.P. (ejemplo)'),
  ('a0000000-0000-4000-8000-000000000005', '900000005-5', 'Limpieza Urbana Colombia S.A.S. E.S.P. (ejemplo)');

-- Cobertura de ejemplo en las principales ciudades:
-- Bogotá 11001, Medellín 05001, Cali 76001, Barranquilla 08001,
-- Cartagena 13001, Bucaramanga 68001.
INSERT INTO catalogo.coberturas (id_empresa, codigo_municipio, tipo_servicio)
SELECT e.id::uuid, m.codigo, e.servicio
FROM (VALUES
        ('a0000000-0000-4000-8000-000000000001', 'ENERGIA',        ARRAY['11001', '05001', '68001']),
        ('a0000000-0000-4000-8000-000000000002', 'ACUEDUCTO',      ARRAY['11001', '68001']),
        ('a0000000-0000-4000-8000-000000000002', 'ALCANTARILLADO', ARRAY['11001', '68001']),
        ('a0000000-0000-4000-8000-000000000003', 'GAS',            ARRAY['76001', '11001', '05001']),
        ('a0000000-0000-4000-8000-000000000004', 'ENERGIA',        ARRAY['08001', '13001', '76001']),
        ('a0000000-0000-4000-8000-000000000004', 'ACUEDUCTO',      ARRAY['08001', '13001', '05001', '76001']),
        ('a0000000-0000-4000-8000-000000000004', 'ALCANTARILLADO', ARRAY['08001', '13001', '05001', '76001']),
        ('a0000000-0000-4000-8000-000000000004', 'GAS',            ARRAY['08001', '13001', '68001']),
        ('a0000000-0000-4000-8000-000000000005', 'ASEO',           ARRAY['11001', '05001', '76001', '08001', '13001', '68001'])
     ) AS e (id, servicio, municipios)
CROSS JOIN LATERAL unnest(e.municipios) AS u (codigo)
JOIN catalogo.municipios m ON m.codigo = u.codigo;

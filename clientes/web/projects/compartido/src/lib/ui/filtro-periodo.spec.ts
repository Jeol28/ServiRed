import { duracion } from './etiquetas';
import { filtroDePeriodo, formularioPeriodo } from './filtro-periodo';

describe('filtro de periodo', () => {
  it('convierte las fechas del formulario en el día completo en hora de Colombia', () => {
    const f = { ...formularioPeriodo(30), desde: '2026-01-01', hasta: '2026-01-31', codigoMunicipio: ' 11001 ' };
    expect(filtroDePeriodo(f)).toEqual({
      desde: '2026-01-01T00:00:00-05:00', hasta: '2026-01-31T23:59:59-05:00', tipoServicio: undefined,
      codigoMunicipio: '11001', codigoDepartamento: undefined, situacion: undefined,
    });
  });

  it('el formulario inicial cubre los últimos N días', () => {
    const f = formularioPeriodo(30);
    const dias = (Date.parse(f.hasta) - Date.parse(f.desde)) / 86_400_000;
    expect(dias).toBe(30);
  });
});

describe('duracion', () => {
  it('formatea minutos', () => {
    expect(duracion(45)).toBe('45 min');
    expect(duracion(135)).toBe('2 h 15 min');
    expect(duracion(undefined)).toBe('—');
  });
});

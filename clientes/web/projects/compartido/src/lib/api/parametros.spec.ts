import { parametros } from './parametros';

describe('parametros', () => {
  it('omite los valores vacíos, nulos y falsos', () => {
    const p = parametros({ estado: 'RECIBIDO', tipoServicio: undefined, codigoMunicipio: '', mios: false,
      sinEmpresa: true, pagina: 0, x: null });
    expect(p.toString()).toBe('estado=RECIBIDO&sinEmpresa=true&pagina=0');
  });
});

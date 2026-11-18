import { TestBed } from '@angular/core/testing';
import { FiltroTerritorio, SelectorTerritorio } from './selector-territorio';
import { CatalogoTerritorial } from './territorio';

describe('Selector de departamento y municipio', () => {
  async function crear(f: FiltroTerritorio) {
    TestBed.configureTestingModule({ imports: [SelectorTerritorio] });
    await TestBed.inject(CatalogoTerritorial).cargar();
    const fix = TestBed.createComponent(SelectorTerritorio);
    fix.componentRef.setInput('f', f);
    fix.detectChanges();
    await fix.whenStable();
    fix.detectChanges();
    const [dep, mun] = [...fix.nativeElement.querySelectorAll('select')] as HTMLSelectElement[];
    return { fix, dep, mun };
  }

  const opciones = (s: HTMLSelectElement) => [...s.options].map((o) => o.textContent!.trim());

  it('el catálogo tiene los 33 departamentos y los 1122 municipios del DANE', async () => {
    const c = TestBed.inject(CatalogoTerritorial);
    await c.cargar();
    expect(c.departamentos()).toHaveLength(33);
    expect(c.departamentos().flatMap((d) => d.municipios)).toHaveLength(1122);
  });

  it('sin departamento, el municipio está deshabilitado', async () => {
    const { dep, mun } = await crear({ codigoDepartamento: '', codigoMunicipio: '' });
    expect(opciones(dep)).toContain('Antioquia');
    expect(mun.disabled).toBe(true);
  });

  it('al elegir un departamento solo ofrece sus municipios y reinicia el municipio', async () => {
    const f = { codigoDepartamento: '', codigoMunicipio: '' };
    const { fix, dep, mun } = await crear(f);
    dep.value = '05';
    dep.dispatchEvent(new Event('change'));
    fix.detectChanges();
    await fix.whenStable();
    expect(f.codigoDepartamento).toBe('05');
    expect(mun.disabled).toBe(false);
    expect(opciones(mun)).toContain('Medellín');
    expect(opciones(mun)).not.toContain('Santiago de Cali');
    expect(opciones(mun)).toHaveLength(126); // «Todos» + 125 municipios de Antioquia

    mun.value = '05001';
    mun.dispatchEvent(new Event('change'));
    expect(f.codigoMunicipio).toBe('05001');

    dep.value = '76';
    dep.dispatchEvent(new Event('change'));
    fix.detectChanges();
    expect(f).toEqual({ codigoDepartamento: '76', codigoMunicipio: '' });
    expect(opciones(mun)).toContain('Santiago de Cali');
  });
});

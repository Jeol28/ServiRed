import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Cuentas } from './cuentas';

describe('Cuentas', () => {
  it('busca por fragmento del correo y vuelve a la primera página', () => {
    TestBed.configureTestingModule({ imports: [Cuentas], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const backend = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(Cuentas);
    f.detectChanges();
    const inicial = backend.expectOne((r) => r.url === '/api/v1/cuentas');
    expect(inicial.request.params.has('correo')).toBe(false);
    inicial.flush({ elementos: [], total: 0, pagina: 0, tamano: 20 });

    const c = f.componentInstance as unknown as { filtroCorreo: string; buscar(p: number): void };
    c.filtroCorreo = '  jorge@  ';
    c.buscar(0);
    const busqueda = backend.expectOne((r) => r.url === '/api/v1/cuentas');
    expect(busqueda.request.params.get('correo')).toBe('jorge@');
    expect(busqueda.request.params.get('pagina')).toBe('0');
    busqueda.flush({ elementos: [], total: 0, pagina: 0, tamano: 20 });
    backend.expectOne('/api/v1/empresas').flush([]);
    backend.verify();
  });

  it('busca la empresa del operador por nombre (sin tildes) o NIT', () => {
    TestBed.configureTestingModule({ imports: [Cuentas], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const backend = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(Cuentas);
    f.detectChanges();
    backend.expectOne((r) => r.url === '/api/v1/cuentas').flush({ elementos: [], total: 0, pagina: 0, tamano: 20 });
    backend.expectOne('/api/v1/empresas').flush([
      { idEmpresa: 'e1', nit: '860063875', nombre: 'ENEL COLOMBIA S.A. E.S.P.' },
      { idEmpresa: 'e2', nit: '901146434', nombre: 'ÁREA LIMPIA DISTRITO CAPITAL S.A.S. E.S.P.' },
    ]);
    const c = f.componentInstance as unknown as {
      busquedaEmpresa: { set(v: string): void }; empresasEncontradas(): { idEmpresa: string }[]; empresa(id: string): string;
    };
    c.busquedaEmpresa.set('area limpia');
    expect(c.empresasEncontradas().map((e) => e.idEmpresa)).toEqual(['e2']);
    c.busquedaEmpresa.set('860063');
    expect(c.empresasEncontradas().map((e) => e.idEmpresa)).toEqual(['e1']);
    expect(c.empresa('e1')).toBe('ENEL COLOMBIA S.A. E.S.P.');
    expect(c.empresa('desconocida')).toBe('desconocida');
  });
});

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Caso, Rol, ServicioSesion } from 'compartido';
import { DetalleCaso } from './detalle-caso';

const CASO: Caso = {
  idCaso: 'k1', idReporte: 'r1', tipoServicio: 'ENERGIA', codigoMunicipio: '11001', nombreMunicipio: 'Bogotá, D.C.',
  magnitud: 'CUADRA', estado: 'RECIBIDO', abiertoEn: '2026-11-17T10:00:00Z', idEmpresa: 'a0000000-0000-4000-8000-000000000001',
  prioridad: 'MEDIA', prioridadManual: false, corteValidado: false, version: 0, historia: [], evidencias: [],
};

describe('DetalleCaso', () => {
  let backend: HttpTestingController;

  async function abrir(rol: Rol, caso: Caso = CASO) {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [DetalleCaso],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    TestBed.inject(ServicioSesion).iniciar('x@y.co', 'una clave larga').subscribe();
    backend.expectOne('/api/v1/auth/login').flush({
      tokenAcceso: 'A', tipoToken: 'Bearer', expiraEn: 900, tokenRefresco: 'R', cuenta: { idCuenta: 'yo', rol },
    });
    const f = TestBed.createComponent(DetalleCaso);
    f.componentRef.setInput('idCaso', caso.idCaso);
    f.detectChanges();
    backend.expectOne('/api/v1/casos/k1').flush(caso);
    f.detectChanges();
    await f.whenStable();
    return f;
  }

  const botones = (f: { nativeElement: HTMLElement }) =>
    [...f.nativeElement.querySelectorAll('button')].map((b) => b.textContent!.trim());

  it('el operador ve las acciones de un caso recibido', async () => {
    const f = await abrir('OPERADOR');
    expect(botones(f)).toEqual(expect.arrayContaining(['Atender', 'Asignármelo', 'Resolver', 'Cerrar caso']));
    expect(botones(f)).not.toContain('Enrutar');
  });

  it('el regulador solo consulta', async () => {
    const f = await abrir('REGULADOR');
    expect(botones(f)).not.toContain('Atender');
    expect(f.nativeElement.textContent).not.toContain('Acciones');
  });

  it('el administrador puede enrutar', async () => {
    const f = await abrir('ADMINISTRADOR');
    expect(botones(f)).toContain('Enrutar');
  });

  it('un caso cerrado no ofrece acciones', async () => {
    const f = await abrir('OPERADOR', { ...CASO, estado: 'CERRADO' });
    expect(f.nativeElement.textContent).not.toContain('Acciones');
  });

  it('atender llama a la API y recarga el detalle', async () => {
    const f = await abrir('OPERADOR');
    const atender = [...f.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) => b.textContent!.trim() === 'Atender') as HTMLButtonElement;
    atender.click();
    backend.expectOne({ method: 'POST', url: '/api/v1/casos/k1/atender' }).flush({ ...CASO, estado: 'EN_ATENCION' });
    backend.expectOne('/api/v1/casos/k1').flush({ ...CASO, estado: 'EN_ATENCION', asignadoA: 'yo' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Caso en atención');
    expect(f.nativeElement.textContent).toContain('Usted');
  });

  it('un conflicto de versión recarga el caso y avisa', async () => {
    const f = await abrir('OPERADOR');
    const c = f.componentInstance as unknown as { ejecutar(o: unknown, m: string): void; api: { atender(id: string): unknown } };
    c.ejecutar(c.api.atender('k1'), 'ok');
    backend.expectOne('/api/v1/casos/k1/atender').flush(
      { detail: 'Otro usuario cambió el caso', motivo: 'VERSION' }, { status: 409, statusText: 'Conflict' });
    backend.expectOne('/api/v1/casos/k1').flush({ ...CASO, version: 1, estado: 'EN_ATENCION' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Otro usuario cambió el caso');
  });
});

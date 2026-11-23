import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Caso, Rol, ServicioSesion } from 'compartido';
import { DetalleCaso } from './detalle-caso';

const CASO: Caso = {
  idCaso: 'k1', idReporte: 'r1', tipoServicio: 'ENERGIA', codigoMunicipio: '11001', nombreMunicipio: 'Bogotá, D.C.',
  magnitud: 'CUADRA', estado: 'RECIBIDO', abiertoEn: '2026-11-17T10:00:00Z', idEmpresa: '619a2e48-402d-5782-9daf-57f16169b157',
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

  it('el administrador solo puede enrutar a la empresa dueña de la zona', async () => {
    const f = await abrir('ADMINISTRADOR', { ...CASO, idEmpresa: null as unknown as string });
    backend.expectOne('/api/v1/casos/k1/zona').flush({
      idZona: '11001-L11', nombreZona: 'Suba', idEmpresa: 'e-suba', nombreEmpresa: 'ÁREA LIMPIA',
    });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Zona: Suba · ÁREA LIMPIA');
    expect(botones(f)).toContain('Enrutar a ÁREA LIMPIA');

    const boton = [...f.nativeElement.querySelectorAll('button')]
      .find((b: HTMLButtonElement) => b.textContent!.trim() === 'Enrutar a ÁREA LIMPIA') as HTMLButtonElement;
    boton.click();
    const r = backend.expectOne({ method: 'PUT', url: '/api/v1/casos/k1/empresa' });
    expect(r.request.body).toEqual({ idEmpresa: 'e-suba' });
  });

  it('si el caso ya es de la dueña de su zona no hay a quién enrutarlo', async () => {
    const f = await abrir('ADMINISTRADOR');
    backend.expectOne('/api/v1/casos/k1/zona').flush({
      idZona: '11001-U', nombreZona: 'Cabecera de Bogotá', idEmpresa: CASO.idEmpresa, nombreEmpresa: 'ENEL',
    });
    f.detectChanges();
    expect(botones(f).some((b) => b.startsWith('Enrutar'))).toBe(false);
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

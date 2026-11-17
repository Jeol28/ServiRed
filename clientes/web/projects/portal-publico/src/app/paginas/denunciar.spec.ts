import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Denunciar } from './denunciar';

interface Interno {
  tipoServicio: string;
  descripcion: string;
  elegir(u: { latitud: number; longitud: number }): void;
  elegirFoto(e: Event): void;
  enviar(): void;
}

describe('Denunciar', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Denunciar],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('no se puede enviar sin ubicación', async () => {
    const f = TestBed.createComponent(Denunciar);
    f.detectChanges();
    await f.whenStable();
    const c = f.componentInstance as unknown as Interno;
    c.descripcion = 'Hay una conexión ilegal en el poste';
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    const enviar = f.nativeElement.querySelector('button[type=submit]') as HTMLButtonElement;
    expect(enviar.disabled).toBe(true);
    c.enviar();
    backend.expectNone('/api/v1/denuncias');
  });

  it('envía la denuncia sin ningún identificador y muestra la confirmación', () => {
    const f = TestBed.createComponent(Denunciar);
    f.detectChanges();
    const c = f.componentInstance as unknown as Interno;
    c.tipoServicio = 'GAS';
    c.descripcion = '  Conexión ilegal en el medidor  ';
    c.elegir({ latitud: 4.65, longitud: -74.05 });
    c.enviar();
    const req = backend.expectOne('/api/v1/denuncias');
    expect(req.request.headers.keys()).toEqual([]);
    expect(req.request.body).toEqual({
      tipoServicio: 'GAS', ubicacion: { latitud: 4.65, longitud: -74.05 }, descripcion: 'Conexión ilegal en el medidor',
    });
    req.flush({ idDenuncia: 'd1', estado: 'RECIBIDA', fechaRecepcion: '2026-11-17T12:00:00Z', tieneEvidencia: false });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Denuncia recibida');
    // El identificador interno no se muestra: no hay forma de vincularla con quien denuncia.
    expect(f.nativeElement.textContent).not.toContain('d1');
  });

  it('rechaza fotos que no son JPEG o PNG, o de más de 5 MB', () => {
    const f = TestBed.createComponent(Denunciar);
    f.detectChanges();
    const c = f.componentInstance as unknown as Interno;
    const evento = (archivo: File) => {
      const entrada = document.createElement('input');
      Object.defineProperty(entrada, 'files', { value: [archivo] });
      return { target: entrada } as unknown as Event;
    };
    c.elegirFoto(evento(new File(['x'], 'a.gif', { type: 'image/gif' })));
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('La foto debe ser JPEG o PNG.');
    c.elegirFoto(evento(new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'a.jpg', { type: 'image/jpeg' })));
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('La foto supera 5 MB.');
  });
});

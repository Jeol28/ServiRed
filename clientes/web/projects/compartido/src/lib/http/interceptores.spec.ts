import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Sesion } from '../modelos';
import { ServicioSesion } from '../sesion/servicio-sesion';
import { interceptorCorrelacion, interceptorJwt } from './interceptores';

const sesion = (acceso: string, refresco: string): Sesion => ({
  tokenAcceso: acceso, tipoToken: 'Bearer', expiraEn: 900, tokenRefresco: refresco,
  cuenta: { idCuenta: 'c-1', rol: 'ADMINISTRADOR' },
});

describe('interceptores', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let servicio: ServicioSesion;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([interceptorCorrelacion, interceptorJwt])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    servicio = TestBed.inject(ServicioSesion);
  });

  afterEach(() => backend.verify());

  function entrar(acceso = 'A1', refresco = 'R1'): void {
    servicio.iniciar('a@b.co', 'una clave larga').subscribe();
    backend.expectOne('/api/v1/auth/login').flush(sesion(acceso, refresco));
  }

  it('agrega un X-Correlation-Id distinto a cada petición de la API', () => {
    http.get('/api/v1/casos').subscribe();
    http.get('/api/v1/casos').subscribe();
    const [a, b] = backend.match('/api/v1/casos');
    const ida = a.request.headers.get('X-Correlation-Id');
    expect(ida).toMatch(/^[0-9a-f-]{36}$/);
    expect(ida).not.toBe(b.request.headers.get('X-Correlation-Id'));
    a.flush({});
    b.flush({});
  });

  it('no toca peticiones que no son de la API', () => {
    entrar();
    http.get('/assets/x.json').subscribe();
    const req = backend.expectOne('/assets/x.json');
    expect(req.request.headers.has('X-Correlation-Id')).toBe(false);
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('agrega el JWT con sesión, pero nunca en las denuncias anónimas', () => {
    entrar();
    http.get('/api/v1/casos').subscribe();
    const casos = backend.expectOne('/api/v1/casos');
    expect(casos.request.headers.get('Authorization')).toBe('Bearer A1');
    casos.flush({});

    http.post('/api/v1/denuncias', {}).subscribe();
    const denuncia = backend.expectOne('/api/v1/denuncias');
    expect(denuncia.request.headers.has('Authorization')).toBe(false);
    expect(denuncia.request.headers.has('X-Correlation-Id')).toBe(false);
    denuncia.flush({});
  });

  it('ante un 401 renueva una vez y repite la petición con el mismo correlationId', () => {
    entrar('A1', 'R1');
    let respuesta: unknown;
    http.get('/api/v1/casos').subscribe((r) => (respuesta = r));

    const primera = backend.expectOne('/api/v1/casos');
    const correlacion = primera.request.headers.get('X-Correlation-Id');
    primera.flush({}, { status: 401, statusText: 'No autorizado' });

    backend.expectOne('/api/v1/auth/refrescar').flush(sesion('A2', 'R2'));
    const segunda = backend.expectOne('/api/v1/casos');
    expect(segunda.request.headers.get('Authorization')).toBe('Bearer A2');
    expect(segunda.request.headers.get('X-Correlation-Id')).toBe(correlacion);
    segunda.flush({ ok: true });
    expect(respuesta).toEqual({ ok: true });
  });

  it('si la renovación falla, lleva al ingreso', () => {
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    entrar();
    let error: unknown;
    http.get('/api/v1/casos').subscribe({ error: (e) => (error = e) });
    backend.expectOne('/api/v1/casos').flush({}, { status: 401, statusText: 'No autorizado' });
    backend.expectOne('/api/v1/auth/refrescar').flush({}, { status: 401, statusText: 'No autorizado' });

    expect(error).toBeTruthy();
    expect(navegar).toHaveBeenCalledWith('/ingreso');
    expect(servicio.autenticado()).toBe(false);
  });

  it('un 403 no intenta renovar', () => {
    entrar();
    http.get('/api/v1/auditoria/integridad').subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/auditoria/integridad').flush({}, { status: 403, statusText: 'Prohibido' });
    backend.expectNone('/api/v1/auth/refrescar');
    expect(servicio.autenticado()).toBe(true);
  });
});

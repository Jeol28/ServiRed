import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Sesion } from '../modelos';
import { ServicioSesion } from './servicio-sesion';

/** Lo que devuelve el gateway al portal: el refresco va en la cookie HttpOnly, no en el cuerpo. */
const sesion = (acceso: string): Sesion => ({
  tokenAcceso: acceso, tipoToken: 'Bearer', expiraEn: 900,
  cuenta: { idCuenta: 'c-1', rol: 'OPERADOR', idEmpresa: 'e-1' },
});

describe('ServicioSesion', () => {
  let servicio: ServicioSesion;
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    servicio = TestBed.inject(ServicioSesion);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('inicia sesión pidiendo la cookie: ningún token queda al alcance de JavaScript fuera de la memoria', () => {
    servicio.iniciar('op@servired.co', 'una clave larga').subscribe();
    const req = http.expectOne('/api/v1/auth/login');
    expect(req.request.body).toEqual({ correo: 'op@servired.co', clave: 'una clave larga' });
    expect(req.request.headers.get('X-Sesion-Web')).toBe('cookie');
    req.flush(sesion('A1'));

    expect(servicio.autenticado()).toBe(true);
    expect(servicio.rol()).toBe('OPERADOR');
    expect(servicio.tokenAcceso()).toBe('A1');
    expect(sessionStorage.getItem('servired.sesion')).toBe('1');
    expect(JSON.stringify(sessionStorage)).not.toContain('A1');
    expect(sessionStorage.getItem('servired.refresco')).toBeNull();
    expect(localStorage.length).toBe(0);
  });

  it('renovaciones simultáneas comparten una sola petición, sin token en el cuerpo', async () => {
    sessionStorage.setItem('servired.sesion', '1');
    const tokens: string[] = [];
    servicio.renovar().subscribe((t) => tokens.push(t));
    servicio.renovar().subscribe((t) => tokens.push(t));

    // El candado entre pestañas (Web Locks) es asíncrono.
    await new Promise((r) => setTimeout(r, 0));
    const req = http.expectOne('/api/v1/auth/refrescar');
    expect(req.request.body).toEqual({});
    expect(req.request.headers.get('X-Sesion-Web')).toBe('cookie');
    req.flush(sesion('A2'));
    await new Promise((r) => setTimeout(r, 0));

    expect(tokens).toEqual(['A2', 'A2']);
    expect(servicio.tokenAcceso()).toBe('A2');
  });

  it('si la renovación falla, la sesión termina', async () => {
    sessionStorage.setItem('servired.sesion', '1');
    let fallo = false;
    servicio.renovar().subscribe({ error: () => (fallo = true) });
    await new Promise((r) => setTimeout(r, 0));
    http.expectOne('/api/v1/auth/refrescar').flush({}, { status: 401, statusText: 'No autorizado' });
    await new Promise((r) => setTimeout(r, 0));

    expect(fallo).toBe(true);
    expect(servicio.autenticado()).toBe(false);
    expect(servicio.puedeRestaurar()).toBe(false);
  });

  it('cerrar sesión pide al gateway revocar el refresco de la cookie y limpia la pestaña', () => {
    servicio.iniciar('a@b.co', 'una clave larga').subscribe();
    http.expectOne('/api/v1/auth/login').flush(sesion('A1'));

    servicio.cerrar().subscribe();
    const req = http.expectOne('/api/v1/auth/cerrar-sesion');
    expect(req.request.body).toEqual({});
    expect(req.request.headers.get('X-Sesion-Web')).toBe('cookie');
    req.flush(null);
    expect(servicio.autenticado()).toBe(false);
    expect(sessionStorage.getItem('servired.sesion')).toBeNull();
  });

  it('sin sesión, cerrar no llama al servidor', () => {
    servicio.cerrar().subscribe();
    http.expectNone('/api/v1/auth/cerrar-sesion');
  });

  it('tieneRol compara con el rol de la sesión', () => {
    expect(servicio.tieneRol('OPERADOR')).toBe(false);
    servicio.iniciar('a@b.co', 'una clave larga').subscribe();
    http.expectOne('/api/v1/auth/login').flush(sesion('A1'));
    expect(servicio.tieneRol('REGULADOR', 'OPERADOR')).toBe(true);
    expect(servicio.tieneRol('ADMINISTRADOR')).toBe(false);
  });
});

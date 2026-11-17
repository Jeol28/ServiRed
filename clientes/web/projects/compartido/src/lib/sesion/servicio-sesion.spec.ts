import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Sesion } from '../modelos';
import { ServicioSesion } from './servicio-sesion';

const sesion = (acceso: string, refresco: string): Sesion => ({
  tokenAcceso: acceso, tipoToken: 'Bearer', expiraEn: 900, tokenRefresco: refresco,
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

  it('inicia sesión: el token de acceso queda en memoria y el de refresco en sessionStorage', () => {
    servicio.iniciar('op@servired.co', 'una clave larga').subscribe();
    const req = http.expectOne('/api/v1/auth/login');
    expect(req.request.body).toEqual({ correo: 'op@servired.co', clave: 'una clave larga' });
    req.flush(sesion('A1', 'R1'));

    expect(servicio.autenticado()).toBe(true);
    expect(servicio.rol()).toBe('OPERADOR');
    expect(servicio.tokenAcceso()).toBe('A1');
    expect(sessionStorage.getItem('servired.refresco')).toBe('R1');
    expect(JSON.stringify(sessionStorage)).not.toContain('A1');
    expect(localStorage.length).toBe(0);
  });

  it('renovaciones simultáneas comparten una sola petición y el refresco nunca se reenvía', () => {
    sessionStorage.setItem('servired.refresco', 'R1');
    const tokens: string[] = [];
    servicio.renovar().subscribe((t) => tokens.push(t));
    servicio.renovar().subscribe((t) => tokens.push(t));

    // Ya se retiró antes de responder: un fallo no podría reenviarlo.
    expect(sessionStorage.getItem('servired.refresco')).toBeNull();
    const req = http.expectOne('/api/v1/auth/refrescar');
    expect(req.request.body).toEqual({ tokenRefresco: 'R1' });
    req.flush(sesion('A2', 'R2'));

    expect(tokens).toEqual(['A2', 'A2']);
    expect(sessionStorage.getItem('servired.refresco')).toBe('R2');
  });

  it('si la renovación falla, la sesión termina', () => {
    sessionStorage.setItem('servired.refresco', 'R1');
    let fallo = false;
    servicio.renovar().subscribe({ error: () => (fallo = true) });
    http.expectOne('/api/v1/auth/refrescar').flush({}, { status: 401, statusText: 'No autorizado' });

    expect(fallo).toBe(true);
    expect(servicio.autenticado()).toBe(false);
    expect(servicio.puedeRestaurar()).toBe(false);
  });

  it('cerrar sesión revoca el refresco en el servidor y limpia la pestaña', () => {
    servicio.iniciar('a@b.co', 'una clave larga').subscribe();
    http.expectOne('/api/v1/auth/login').flush(sesion('A1', 'R1'));

    servicio.cerrar().subscribe();
    const req = http.expectOne('/api/v1/auth/cerrar-sesion');
    expect(req.request.body).toEqual({ tokenRefresco: 'R1' });
    req.flush(null);
    expect(servicio.autenticado()).toBe(false);
    expect(sessionStorage.getItem('servired.refresco')).toBeNull();
  });

  it('tieneRol compara con el rol de la sesión', () => {
    expect(servicio.tieneRol('OPERADOR')).toBe(false);
    servicio.iniciar('a@b.co', 'una clave larga').subscribe();
    http.expectOne('/api/v1/auth/login').flush(sesion('A1', 'R1'));
    expect(servicio.tieneRol('REGULADOR', 'OPERADOR')).toBe(true);
    expect(servicio.tieneRol('ADMINISTRADOR')).toBe(false);
  });
});

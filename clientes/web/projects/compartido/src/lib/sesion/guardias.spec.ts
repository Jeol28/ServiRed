import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, isObservable } from 'rxjs';
import { guardiaAutenticado, guardiaRol } from './guardias';
import { ServicioSesion } from './servicio-sesion';

const estado = { url: '/casos/1' } as RouterStateSnapshot;
const ruta = {} as ActivatedRouteSnapshot;

async function resolver(r: unknown): Promise<unknown> {
  return isObservable(r) ? firstValueFrom(r as Observable<unknown>) : r;
}

describe('guardias', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
  });

  it('sin sesión redirige al ingreso conservando la ruta', async () => {
    const r = await resolver(TestBed.runInInjectionContext(() => guardiaAutenticado(ruta, estado)));
    expect(TestBed.inject(Router).serializeUrl(r as UrlTree)).toBe('/ingreso?volver=%2Fcasos%2F1');
  });

  it('tras recargar la pestaña restaura la sesión con el refresco', async () => {
    sessionStorage.setItem('servired.refresco', 'R1');
    const promesa = resolver(TestBed.runInInjectionContext(() => guardiaAutenticado(ruta, estado)));
    backend.expectOne('/api/v1/auth/refrescar').flush({
      tokenAcceso: 'A', tipoToken: 'Bearer', expiraEn: 900, tokenRefresco: 'R2', cuenta: { idCuenta: 'c', rol: 'REGULADOR' },
    });
    expect(await promesa).toBe(true);
    expect(TestBed.inject(ServicioSesion).rol()).toBe('REGULADOR');
  });

  it('guardiaRol deja pasar el rol permitido y redirige los demás', async () => {
    TestBed.inject(ServicioSesion).iniciar('a@b.co', 'una clave larga').subscribe();
    backend.expectOne('/api/v1/auth/login').flush({
      tokenAcceso: 'A', tipoToken: 'Bearer', expiraEn: 900, tokenRefresco: 'R', cuenta: { idCuenta: 'c', rol: 'OPERADOR' },
    });
    expect(TestBed.runInInjectionContext(() => guardiaRol('OPERADOR', 'ADMINISTRADOR')(ruta, estado))).toBe(true);
    const r = TestBed.runInInjectionContext(() => guardiaRol('ADMINISTRADOR')(ruta, estado));
    expect(TestBed.inject(Router).serializeUrl(r as UrlTree)).toBe('/sin-permiso');
  });
});

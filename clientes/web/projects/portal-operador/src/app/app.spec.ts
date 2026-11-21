import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Rol, ServicioSesion } from 'compartido';
import { App } from './app';

describe('App (menú por rol)', () => {
  function menu(rol: Rol): string[] {
    sessionStorage.clear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(ServicioSesion).iniciar('x@y.co', 'una clave larga').subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/login').flush({
      tokenAcceso: 'A', tipoToken: 'Bearer', expiraEn: 900, tokenRefresco: 'R', cuenta: { idCuenta: 'c', rol },
    });
    const f = TestBed.createComponent(App);
    f.detectChanges();
    return [...f.nativeElement.querySelectorAll('nav a .texto')].map((a: Element) => a.textContent!.trim());
  }

  it('cada rol ve solo sus secciones', () => {
    expect(menu('OPERADOR')).toEqual(['Casos', 'Mapa', 'Denuncias']);
    expect(menu('REGULADOR')).toEqual(['Casos', 'Mapa', 'Denuncias', 'Exportación']);
    expect(menu('ADMINISTRADOR')).toEqual(['Casos', 'Mapa', 'Denuncias', 'Exportación', 'Cuentas']);
  });

  it('sin sesión no muestra el menú', () => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    const f = TestBed.createComponent(App);
    f.detectChanges();
    expect(f.nativeElement.querySelector('nav')).toBeNull();
  });
});

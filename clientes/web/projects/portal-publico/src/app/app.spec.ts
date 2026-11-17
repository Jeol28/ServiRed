import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App pública', () => {
  it('ofrece mapa, indicadores y datos abiertos, sin ingreso ni denuncias (solo en las apps)', () => {
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([])] });
    const f = TestBed.createComponent(App);
    f.detectChanges();
    const enlaces = [...f.nativeElement.querySelectorAll('nav a')].map((a: Element) => a.textContent!.trim());
    expect(enlaces).toEqual(['Mapa', 'Indicadores', 'Datos abiertos']);
    expect(f.nativeElement.textContent).not.toContain('Ingresar');
    // El personal entra por la misma dirección, en /operacion/ (página completa, otra app).
    const personal = f.nativeElement.querySelector('a[href="/operacion/"]') as HTMLAnchorElement;
    expect(personal.textContent?.trim()).toBe('Acceso del personal');
  });
});

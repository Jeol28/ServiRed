import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App pública', () => {
  function crear() {
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([])] });
    const f = TestBed.createComponent(App);
    f.detectChanges();
    return f;
  }

  it('ofrece mapa, indicadores y datos abiertos en el menú, sin ingreso ni denuncias (solo en las apps)', () => {
    const f = crear();
    const enlaces = [...f.nativeElement.querySelectorAll('nav a .texto')].map((a: Element) => a.textContent!.trim());
    expect(enlaces).toEqual(['Mapa', 'Indicadores', 'Datos abiertos']);
    expect(f.nativeElement.textContent).not.toContain('Ingresar');
    // El personal entra por la misma dirección, en /operacion/ (página completa, otra app).
    const personal = f.nativeElement.querySelector('header a[href="/operacion/"]') as HTMLAnchorElement;
    expect(personal.title).toBe('Acceso del personal');
    expect(personal.textContent).toContain('Acceso del personal');
  });

  it('el menú lateral se abre con ☰ y se cierra con Escape; cerrado no se puede enfocar', () => {
    const f = crear();
    const cajon = f.nativeElement.querySelector('#menu-lateral') as HTMLElement;
    const boton = f.nativeElement.querySelector('button[aria-controls="menu-lateral"]') as HTMLButtonElement;
    expect(cajon.hasAttribute('inert')).toBe(true);
    boton.click();
    f.detectChanges();
    expect(cajon.hasAttribute('inert')).toBe(false);
    expect(boton.getAttribute('aria-expanded')).toBe('true');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    f.detectChanges();
    expect(cajon.hasAttribute('inert')).toBe(true);
  });
});

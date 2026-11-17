import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App pública', () => {
  it('ofrece mapa, indicadores, datos abiertos y denuncia anónima, sin ingreso', () => {
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([])] });
    const f = TestBed.createComponent(App);
    f.detectChanges();
    const enlaces = [...f.nativeElement.querySelectorAll('nav a')].map((a: Element) => a.textContent!.trim());
    expect(enlaces).toEqual(['Mapa', 'Indicadores', 'Datos abiertos', 'Denuncia anónima']);
    expect(f.nativeElement.textContent).not.toContain('Ingresar');
  });
});

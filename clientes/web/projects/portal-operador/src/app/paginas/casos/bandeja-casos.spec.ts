import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BandejaCasos } from './bandeja-casos';

describe('BandejaCasos', () => {
  const vacia = { elementos: [], total: 0, pagina: 0, tamano: 20 };

  it('ordena por prioridad por omisión y, a pedido, por lo más reciente desde la primera página', () => {
    TestBed.configureTestingModule({
      imports: [BandejaCasos],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const backend = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(BandejaCasos);
    f.detectChanges();
    const inicial = backend.expectOne((r) => r.url === '/api/v1/casos');
    expect(inicial.request.params.has('orden')).toBe(false);
    inicial.flush(vacia);

    const c = f.componentInstance as unknown as { ordenar(o: string): void };
    c.ordenar('RECIENTES');
    const recientes = backend.expectOne((r) => r.url === '/api/v1/casos');
    expect(recientes.request.params.get('orden')).toBe('RECIENTES');
    expect(recientes.request.params.get('pagina')).toBe('0');
    recientes.flush(vacia);

    c.ordenar('PRIORIDAD');
    const prioridad = backend.expectOne((r) => r.url === '/api/v1/casos');
    expect(prioridad.request.params.has('orden')).toBe(false);
    prioridad.flush(vacia);
    backend.verify();
  });
});

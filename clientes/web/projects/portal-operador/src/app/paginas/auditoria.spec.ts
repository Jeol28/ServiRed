import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Auditoria } from './auditoria';

describe('Auditoria', () => {
  it('busca por el rol del actor y el tipo de recurso, sin identificadores, y los muestra con nombre', () => {
    TestBed.configureTestingModule({ imports: [Auditoria], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const backend = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(Auditoria);
    f.detectChanges();
    backend.expectOne((r) => r.url === '/api/v1/auditoria/registros').flush({ elementos: [], total: 0, pagina: 0, tamano: 50 });

    const c = f.componentInstance as unknown as {
      filtro: { actorRol?: string; recursoTipo?: string }; buscar(p: number): void;
      nombreRol(r?: string | null): string; nombreRecurso(t?: string | null): string;
    };
    c.filtro.actorRol = 'CIUDADANO';
    c.filtro.recursoTipo = 'REPORTE';
    c.buscar(0);
    const r = backend.expectOne((q) => q.url === '/api/v1/auditoria/registros');
    expect(r.request.params.get('actorRol')).toBe('CIUDADANO');
    expect(r.request.params.get('recursoTipo')).toBe('REPORTE');
    expect(r.request.params.has('actorId')).toBe(false);
    r.flush({
      elementos: [{ secuencia: 7, idEvento: 'e', tipo: 'reporte.creado', origen: 'reportes', categoria: 'REPORTE',
        accion: 'REPORTE_CREADO', actorRol: 'CIUDADANO', actorId: 'a', recursoTipo: 'REPORTE', recursoId: 'x',
        ocurridoEn: '2026-11-17T10:00:00Z', registradoEn: '2026-11-17T10:00:01Z', hash: 'h' }],
      total: 1, pagina: 0, tamano: 50,
    });
    f.detectChanges();
    const fila = f.nativeElement.querySelector('tbody tr') as HTMLElement;
    expect(fila.textContent).toContain('Ciudadano');
    expect(fila.textContent).toContain('Reporte');
    expect(c.nombreRol(null)).toBe('Sistema o anónimo');
    expect(c.nombreRecurso('EXPORTACION')).toBe('Exportación');
    backend.verify();
  });
});

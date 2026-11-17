import { TestBed } from '@angular/core/testing';
import { Conjunto } from './conjunto';

describe('Conjunto', () => {
  it('un conjunto suprimido no muestra ningún valor (k-anonimato)', () => {
    const f = TestBed.createComponent(Conjunto);
    f.componentRef.setInput('titulo', 'Gas');
    f.componentRef.setInput('c', { suprimido: true });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Datos insuficientes');
    expect(f.nativeElement.querySelector('dl')).toBeNull();
  });

  it('muestra los indicadores publicables', () => {
    const f = TestBed.createComponent(Conjunto);
    f.componentRef.setInput('titulo', 'Energía');
    f.componentRef.setInput('c', {
      suprimido: false, reportes: 12, duracion: { mediana: 135, reportesConDuracion: 8 },
      severidad: { indice: 2.4, porMagnitud: {}, porcentajeValidados: 50 }, atencion: { porcentajeResueltos: 75 },
    });
    f.detectChanges();
    const texto = f.nativeElement.textContent;
    expect(texto).toContain('12');
    expect(texto).toContain('2 h 15 min');
    expect(texto).toContain('75 %');
  });
});

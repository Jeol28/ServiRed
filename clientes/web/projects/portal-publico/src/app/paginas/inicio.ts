import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import {
  ApiIndicadores, MapaPublico, MarcaMapa, Mapa, ResumenIndicadores, filtroDePeriodo, formularioPeriodo, mensajeDeError,
} from 'compartido';
import { Conjunto } from './conjunto';
import { FormularioFiltros } from './formulario-filtros';
import { MetadatosPublicacion } from './metadatos';

/**
 * Mapa público (RF-9): celdas de ~1 km con el número de reportes; las celdas
 * con menos de 3 reportes no se publican (k-anonimato, RNF-DAT2).
 */
@Component({
  selector: 'app-inicio',
  imports: [Mapa, Conjunto, FormularioFiltros, MetadatosPublicacion],
  template: `
    <h1>Interrupciones de servicios públicos</h1>
    <p class="sr-suave">
      Reportes ciudadanos agregados por zona. Para reportar un corte o hacer una denuncia anónima,
      use la app de SERVI-RED en su teléfono.
    </p>
    <app-formulario-filtros [f]="f" (aplicar)="cargar()" />
    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }
    <div class="disposicion">
      <section class="sr-tarjeta">
        <p class="sr-suave" aria-live="polite">
          {{ mapa()?.celdas?.length ?? 0 }} zonas con reportes. Cada círculo agrupa los reportes de una celda de
          {{ mapa()?.celdaGrados ?? 0.01 }}° (~1 km).
        </p>
        <sr-mapa [marcas]="marcas()" alto="520px" titulo="Mapa de zonas con interrupciones reportadas" />
      </section>
      @if (resumen(); as r) {
        <section class="sr-tarjeta">
          <app-conjunto titulo="Resumen del periodo" [c]="r.indicadores" />
        </section>
      }
    </div>
    @if (mapa(); as m) { <app-metadatos [m]="m.metadatos" /> }
  `,
  styles: `
    .disposicion { display: grid; gap: 1rem; grid-template-columns: minmax(0, 3fr) minmax(240px, 1fr); align-items: start; }
    @media (max-width: 800px) { .disposicion { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Inicio {
  private readonly api = inject(ApiIndicadores);
  protected f = formularioPeriodo(30);
  protected readonly mapa = signal<MapaPublico | null>(null);
  protected readonly resumen = signal<ResumenIndicadores | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly marcas = computed<MarcaMapa[]>(() => {
    const celdas = this.mapa()?.celdas ?? [];
    const maximo = Math.max(1, ...celdas.map((c) => c.reportes));
    return celdas.map((c) => ({
      latitud: c.latitud,
      longitud: c.longitud,
      color: '#c2410c',
      radio: 6 + 14 * Math.sqrt(c.reportes / maximo),
      etiqueta: `${c.reportes} reportes en esta zona`,
    }));
  });

  constructor() {
    this.cargar();
  }

  protected cargar(): void {
    this.error.set(null);
    const filtro = filtroDePeriodo(this.f);
    this.api.mapaPublico(filtro).subscribe({
      next: (m) => this.mapa.set(m),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
    this.api.resumen(filtro).subscribe({
      next: (r) => this.resumen.set(r),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }
}

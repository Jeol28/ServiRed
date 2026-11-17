import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  ApiIndicadores, CatalogoDatosAbiertos, descargar, filtroDePeriodo, formularioPeriodo, mensajeDeError,
} from 'compartido';
import { FormularioFiltros } from './formulario-filtros';
import { MetadatosPublicacion } from './metadatos';

/** Datos abiertos (RF-10, RNF-DAT2): solo agregados mensuales por municipio y servicio. */
@Component({
  selector: 'app-datos-abiertos',
  imports: [FormularioFiltros, MetadatosPublicacion],
  template: `
    <h1>Datos abiertos</h1>
    <p class="sr-suave">
      Conjuntos agregados y anonimizados. Las combinaciones con menos de 3 reportes aparecen suprimidas.
    </p>
    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }
    @if (catalogo(); as cat) {
      @for (c of cat.conjuntos; track c.id) {
        <section class="sr-tarjeta">
          <h2>{{ c.titulo }}</h2>
          <p><strong>Licencia:</strong> {{ c.licencia }}</p>
          <p><strong>Columnas:</strong> <code>{{ c.columnas.join(', ') }}</code></p>
          <app-formulario-filtros [f]="f" (aplicar)="bajar('csv')" />
          <div class="sr-fila">
            @for (formato of c.formatos; track formato) {
              <button type="button" class="sr-primario" [disabled]="ocupado()" (click)="bajar(formato)">
                Descargar {{ formato.toUpperCase() }}
              </button>
            }
          </div>
        </section>
      }
      <app-metadatos [m]="cat.metadatos" />
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DatosAbiertos {
  private readonly api = inject(ApiIndicadores);
  protected f = formularioPeriodo(365);
  protected readonly catalogo = signal<CatalogoDatosAbiertos | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly ocupado = signal(false);

  constructor() {
    this.api.catalogoDatosAbiertos().subscribe({
      next: (c) => this.catalogo.set(c),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }

  protected bajar(formato: string): void {
    const f = formato === 'json' ? 'json' : 'csv';
    this.ocupado.set(true);
    this.error.set(null);
    this.api.datosAbiertosMunicipales(filtroDePeriodo(this.f), f).subscribe({
      next: (blob) => {
        this.ocupado.set(false);
        descargar(blob, `servired-indicadores-municipales.${f}`);
      },
      error: (e: unknown) => {
        this.ocupado.set(false);
        this.error.set(mensajeDeError(e));
      },
    });
  }
}

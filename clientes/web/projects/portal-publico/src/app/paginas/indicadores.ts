import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiIndicadores, DuracionPipe, IndicadoresTerritoriales, NOMBRE_SERVICIO, ResumenIndicadores, filtroDePeriodo,
  formularioPeriodo, mensajeDeError,
} from 'compartido';
import { Conjunto } from './conjunto';
import { FormularioFiltros } from './formulario-filtros';
import { MetadatosPublicacion } from './metadatos';

/** Indicadores de calidad del servicio (RF-8): resumen por servicio y por territorio. */
@Component({
  selector: 'app-indicadores',
  imports: [FormsModule, DecimalPipe, DuracionPipe, Conjunto, FormularioFiltros, MetadatosPublicacion],
  template: `
    <h1>Indicadores</h1>
    <app-formulario-filtros [f]="f" (aplicar)="cargar()" />
    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }

    @if (resumen(); as r) {
      <div class="sr-rejilla">
        <section class="sr-tarjeta"><app-conjunto titulo="Todos los servicios" [c]="r.indicadores" /></section>
        @for (s of r.porServicio; track s.tipoServicio) {
          <section class="sr-tarjeta"><app-conjunto [titulo]="nombreServicio[s.tipoServicio]" [c]="s.indicadores" /></section>
        }
      </div>
    }

    <section class="sr-tarjeta">
      <div class="sr-fila encabezado">
        <h2>Por territorio</h2>
        <label>Nivel
          <select name="nivel" [(ngModel)]="nivel" (ngModelChange)="cargarTerritorial()">
            <option ngValue="DEPARTAMENTO">Departamento</option>
            <option ngValue="MUNICIPIO">Municipio</option>
          </select>
        </label>
      </div>
      @if (territorial(); as t) {
        <div class="sr-desplazable">
          <table class="sr-tabla">
            <thead>
              <tr><th>Territorio</th><th>Reportes</th><th>Duración mediana</th><th>Severidad</th><th>% resueltos</th></tr>
            </thead>
            <tbody>
              @for (x of t.territorios; track x.codigo) {
                <tr>
                  <td>{{ x.nombre }} @if (x.departamento) { <span class="sr-suave">({{ x.departamento }})</span> }</td>
                  @if (x.indicadores.suprimido) {
                    <td colspan="4" class="sr-suave">Menos de 3 reportes: no se publica</td>
                  } @else {
                    <td>{{ x.indicadores.reportes }}</td>
                    <td>{{ x.indicadores.duracion?.mediana | duracion }}</td>
                    <td>{{ x.indicadores.severidad?.indice ?? '—' }}</td>
                    <td>{{ x.indicadores.atencion?.porcentajeResueltos != null ? (x.indicadores.atencion!.porcentajeResueltos | number: '1.0-1') : '—' }}</td>
                  }
                </tr>
              } @empty {
                <tr><td colspan="5" class="sr-suave">Sin datos en el periodo.</td></tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
    @if (resumen(); as r) { <app-metadatos [m]="r.metadatos" /> }
  `,
  styles: `.encabezado { justify-content: space-between; align-items: center; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Indicadores {
  private readonly api = inject(ApiIndicadores);
  protected readonly nombreServicio = NOMBRE_SERVICIO;
  protected f = formularioPeriodo(90);
  protected nivel: 'DEPARTAMENTO' | 'MUNICIPIO' = 'DEPARTAMENTO';
  protected readonly resumen = signal<ResumenIndicadores | null>(null);
  protected readonly territorial = signal<IndicadoresTerritoriales | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.cargar();
  }

  protected cargar(): void {
    this.error.set(null);
    this.api.resumen(filtroDePeriodo(this.f)).subscribe({
      next: (r) => this.resumen.set(r),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
    this.cargarTerritorial();
  }

  protected cargarTerritorial(): void {
    this.api.territorial(this.nivel, filtroDePeriodo(this.f)).subscribe({
      next: (t) => this.territorial.set(t),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }
}

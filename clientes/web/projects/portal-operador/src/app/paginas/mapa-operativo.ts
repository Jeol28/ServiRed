import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiIndicadores, COLOR_SERVICIO, MarcaMapa, Mapa, NOMBRE_ESTADO, NOMBRE_PRIORIDAD, NOMBRE_SERVICIO,
  PuntoOperativo, TIPOS_SERVICIO, fechaCorta, filtroDePeriodo, formularioPeriodo, mensajeDeError,
} from 'compartido';

/** Mapa operativo (RF-9): puntos exactos de los reportes, solo para el personal. */
@Component({
  selector: 'app-mapa-operativo',
  imports: [FormsModule, Mapa],
  template: `
    <h1>Mapa operativo</h1>
    <form class="sr-tarjeta sr-fila" (ngSubmit)="cargar()" aria-label="Filtros del mapa">
      <label>Desde <input type="date" name="desde" [(ngModel)]="f.desde" /></label>
      <label>Hasta <input type="date" name="hasta" [(ngModel)]="f.hasta" /></label>
      <label>Servicio
        <select name="tipo" [(ngModel)]="f.tipoServicio">
          <option [ngValue]="undefined">Todos</option>
          @for (t of tipos; track t) { <option [ngValue]="t">{{ nombreServicio[t] }}</option> }
        </select>
      </label>
      <label>Situación
        <select name="situacion" [(ngModel)]="f.situacion">
          <option [ngValue]="undefined">Todas</option>
          <option ngValue="ACTIVO">Activos</option>
          <option ngValue="RESUELTO">Resueltos</option>
        </select>
      </label>
      <label>Municipio <input name="municipio" [(ngModel)]="f.codigoMunicipio" maxlength="5" size="7" placeholder="11001" /></label>
      <button class="sr-primario" type="submit">Actualizar</button>
    </form>
    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }
    <section class="sr-tarjeta">
      <p class="sr-suave" aria-live="polite">{{ puntos().length }} reportes en el mapa. Clic en un punto para ver su información.</p>
      <div class="leyenda">
        @for (t of tipos; track t) {
          <span><span class="punto" [style.background]="colores[t]"></span>{{ nombreServicio[t] }}</span>
        }
      </div>
      <sr-mapa [marcas]="marcas()" alto="560px" titulo="Mapa operativo de reportes" />
    </section>
  `,
  styles: `
    .leyenda { display: flex; flex-wrap: wrap; gap: .4rem 1rem; margin-bottom: .5rem; font-size: .88rem; }
    .punto { display: inline-block; width: .8rem; height: .8rem; border-radius: 50%; margin-right: .3rem; vertical-align: -1px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MapaOperativo {
  private readonly api = inject(ApiIndicadores);

  protected readonly tipos = TIPOS_SERVICIO;
  protected readonly nombreServicio = NOMBRE_SERVICIO;
  protected readonly colores = COLOR_SERVICIO;
  protected f = formularioPeriodo(7);
  protected readonly puntos = signal<PuntoOperativo[]>([]);
  protected readonly error = signal<string | null>(null);

  protected readonly marcas = computed<MarcaMapa[]>(() => this.puntos().map((p) => ({
    latitud: p.latitud,
    longitud: p.longitud,
    color: COLOR_SERVICIO[p.tipoServicio],
    radio: p.prioridad === 'CRITICA' ? 11 : p.prioridad === 'ALTA' ? 9 : 7,
    etiqueta: [
      NOMBRE_SERVICIO[p.tipoServicio],
      p.estadoCaso ? `Estado: ${NOMBRE_ESTADO[p.estadoCaso]}` : 'Sin caso',
      p.prioridad ? `Prioridad: ${NOMBRE_PRIORIDAD[p.prioridad]}` : '',
      p.validado ? 'Validado por la comunidad' : '',
      fechaCorta(p.fechaRegistro),
    ].filter(Boolean).join('\n'),
  })));

  constructor() {
    this.cargar();
  }

  protected cargar(): void {
    this.error.set(null);
    this.api.mapaOperativo(filtroDePeriodo(this.f)).subscribe({
      next: (p) => this.puntos.set(p),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }
}

import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FormularioPeriodo, NOMBRE_SERVICIO, SelectorTerritorio, TIPOS_SERVICIO } from 'compartido';

/** Filtros de periodo, servicio y territorio comunes a las páginas públicas. */
@Component({
  selector: 'app-formulario-filtros',
  imports: [FormsModule, SelectorTerritorio],
  template: `
    <form class="sr-tarjeta filtros" (ngSubmit)="aplicar.emit()" aria-label="Filtros">
      <label>Desde <input type="date" name="desde" [(ngModel)]="f().desde" /></label>
      <label>Hasta <input type="date" name="hasta" [(ngModel)]="f().hasta" /></label>
      <label>Servicio
        <select name="tipo" [(ngModel)]="f().tipoServicio">
          <option [ngValue]="undefined">Todos</option>
          @for (t of tipos; track t) { <option [ngValue]="t">{{ nombreServicio[t] }}</option> }
        </select>
      </label>
      <label>Situación
        <select name="situacion" [(ngModel)]="f().situacion">
          <option [ngValue]="undefined">Todas</option>
          <option ngValue="ACTIVO">Activas</option>
          <option ngValue="RESUELTO">Resueltas</option>
        </select>
      </label>
      <sr-selector-territorio [f]="f()" />
      <button class="sr-primario" type="submit">Aplicar</button>
    </form>
  `,
  styles: `
    /* Columnas iguales: todos los campos del mismo ancho, sea fecha, lista o texto. */
    .filtros { display: grid; gap: .75rem; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); align-items: end; }
    .filtros label { min-width: 0; }
    .filtros input, .filtros select { width: 100%; min-width: 0; height: 2.4rem; }
    .filtros button { justify-content: center; height: 2.4rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormularioFiltros {
  /** El objeto se edita en el lugar; el padre lo lee al recibir `aplicar`. */
  readonly f = input.required<FormularioPeriodo>();
  readonly aplicar = output<void>();
  protected readonly tipos = TIPOS_SERVICIO;
  protected readonly nombreServicio = NOMBRE_SERVICIO;
}

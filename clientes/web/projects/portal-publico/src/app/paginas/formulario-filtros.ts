import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FormularioPeriodo, NOMBRE_SERVICIO, SelectorTerritorio, TIPOS_SERVICIO } from 'compartido';

/** Filtros de periodo, servicio y territorio comunes a las páginas públicas. */
@Component({
  selector: 'app-formulario-filtros',
  imports: [FormsModule, SelectorTerritorio],
  template: `
    <form class="sr-tarjeta sr-filtros" (ngSubmit)="aplicar.emit()" aria-label="Filtros">
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
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormularioFiltros {
  /** El objeto se edita en el lugar; el padre lo lee al recibir `aplicar`. */
  readonly f = input.required<FormularioPeriodo>();
  readonly aplicar = output<void>();
  protected readonly tipos = TIPOS_SERVICIO;
  protected readonly nombreServicio = NOMBRE_SERVICIO;
}

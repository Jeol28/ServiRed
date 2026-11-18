import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogoTerritorial } from './territorio';

/** Lo que el selector edita: los códigos DIVIPOLA ('' = todos). */
export interface FiltroTerritorio {
  codigoDepartamento: string;
  codigoMunicipio: string;
}

/**
 * Departamento y municipio como listas desplegables: al elegir un departamento
 * solo se ofrecen sus municipios. Edita los códigos del objeto en el lugar.
 * El host no genera caja (`display: contents`): las dos etiquetas se acomodan
 * como dos campos más del formulario que lo contiene.
 */
@Component({
  selector: 'sr-selector-territorio',
  imports: [FormsModule],
  template: `
    <label>Departamento
      <select name="departamento" [ngModel]="departamento()" (ngModelChange)="elegirDepartamento($event)">
        <option value="">Todos</option>
        @for (d of catalogo.departamentos(); track d.codigo) { <option [value]="d.codigo">{{ d.nombre }}</option> }
      </select>
    </label>
    <label>Municipio
      <select name="municipio" [ngModel]="municipio()" (ngModelChange)="elegirMunicipio($event)"
              [disabled]="!departamento()" [attr.aria-describedby]="departamento() ? null : 'ayuda-municipio'">
        <option value="">{{ departamento() ? 'Todos' : 'Elija un departamento' }}</option>
        @for (m of municipios(); track m.codigo) { <option [value]="m.codigo">{{ m.nombre }}</option> }
      </select>
      <span id="ayuda-municipio" class="sr-oculto">Primero elija un departamento.</span>
    </label>
  `,
  styles: `
    :host { display: contents; }
    /* Del mismo ancho que los demás campos: sin esto, la lista toma el ancho de su opción más larga. */
    label { min-width: 0; }
    select { width: 100%; min-width: 0; height: 2.4rem; text-overflow: ellipsis; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SelectorTerritorio implements OnInit {
  protected readonly catalogo = inject(CatalogoTerritorial);
  readonly f = input.required<FiltroTerritorio>();

  // Copias reactivas de los códigos, para que la lista de municipios siga al departamento.
  protected readonly departamento = signal('');
  protected readonly municipio = signal('');

  protected readonly municipios = computed(() =>
    this.catalogo.departamentos().find((d) => d.codigo === this.departamento())?.municipios ?? []);

  constructor() {
    void this.catalogo.cargar();
  }

  ngOnInit(): void {
    this.departamento.set(this.f().codigoDepartamento);
    this.municipio.set(this.f().codigoMunicipio);
  }

  protected elegirDepartamento(codigo: string): void {
    this.departamento.set(codigo);
    this.municipio.set('');
    this.f().codigoDepartamento = codigo;
    this.f().codigoMunicipio = '';
  }

  protected elegirMunicipio(codigo: string): void {
    this.municipio.set(codigo);
    this.f().codigoMunicipio = codigo;
  }
}

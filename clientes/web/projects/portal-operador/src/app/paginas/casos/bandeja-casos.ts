import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ApiCasos, COLOR_PRIORIDAD, Caso, ESTADOS_CASO, FechaCortaPipe, FiltroCasos, NOMBRE_ESTADO, NOMBRE_MAGNITUD,
  NOMBRE_PRIORIDAD, NOMBRE_SERVICIO, PRIORIDADES, Pagina, SelectorTerritorio, ServicioSesion, TIPOS_SERVICIO,
  ORDENES_CASOS, OrdenCasos, mensajeDeError,
} from 'compartido';
import { Empresas } from '../../empresas';

const TAMANO = 20;

/** Bandeja de casos (RF-6): filtros, orden por prioridad y paginación. */
@Component({
  selector: 'app-bandeja-casos',
  imports: [FormsModule, RouterLink, FechaCortaPipe, SelectorTerritorio],
  template: `
    <h1>Casos</h1>
    <form class="sr-tarjeta sr-filtros" (ngSubmit)="buscar(0)" aria-label="Filtros">
      <label>Estado
        <select name="estado" [(ngModel)]="filtro.estado">
          <option [ngValue]="undefined">Todos</option>
          @for (e of estados; track e) { <option [ngValue]="e">{{ nombreEstado[e] }}</option> }
        </select>
      </label>
      <label>Servicio
        <select name="tipo" [(ngModel)]="filtro.tipoServicio">
          <option [ngValue]="undefined">Todos</option>
          @for (t of tipos; track t) { <option [ngValue]="t">{{ nombreServicio[t] }}</option> }
        </select>
      </label>
      <label>Prioridad
        <select name="prioridad" [(ngModel)]="filtro.prioridad">
          <option [ngValue]="undefined">Todas</option>
          @for (p of prioridades; track p) { <option [ngValue]="p">{{ nombrePrioridad[p] }}</option> }
        </select>
      </label>
      <sr-selector-territorio [f]="territorio" />
      <label>Ordenar por
        <select name="orden" [ngModel]="filtro.orden ?? 'PRIORIDAD'" (ngModelChange)="ordenar($event)">
          @for (o of ordenes; track o.valor) { <option [ngValue]="o.valor">{{ o.nombre }}</option> }
        </select>
      </label>
      @if (esOperador()) {
        <label class="sr-en-linea"><input type="checkbox" name="mios" [(ngModel)]="filtro.mios" /> Solo los míos</label>
      }
      @if (esAdmin()) {
        <label class="sr-en-linea">
          <input type="checkbox" name="sinEmpresa" [(ngModel)]="filtro.sinEmpresa" /> Sin empresa
        </label>
      }
      <button class="sr-primario" type="submit">Buscar</button>
    </form>

    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }

    <section class="sr-tarjeta">
      @if (pagina(); as p) {
        <p class="sr-suave" aria-live="polite">{{ p.total }} casos</p>
        <div class="sr-desplazable">
          <table class="sr-tabla">
            <thead>
              <tr>
                <th>Prioridad</th><th>Servicio</th><th>Municipio</th><th>Magnitud</th><th>Estado</th>
                <th>Empresa</th><th>Abierto</th>
              </tr>
            </thead>
            <tbody>
              @for (c of p.elementos; track c.idCaso) {
                <tr>
                  <td><span class="sr-chip" [style.color]="colorPrioridad[c.prioridad]">{{ nombrePrioridad[c.prioridad] }}</span></td>
                  <td><a [routerLink]="['/casos', c.idCaso]">{{ nombreServicio[c.tipoServicio] }}</a>
                    @if (c.corteValidado) { <span class="sr-suave" title="Validado por la comunidad"> ✓</span> }
                  </td>
                  <td>{{ c.nombreMunicipio ?? c.codigoMunicipio }}</td>
                  <td>{{ nombreMagnitud[c.magnitud] }}</td>
                  <td>{{ nombreEstado[c.estado] }}</td>
                  <td>{{ empresa(c.idEmpresa) }}</td>
                  <td>{{ c.abiertoEn | fechaCorta }}</td>
                </tr>
              } @empty {
                <tr><td colspan="7" class="sr-suave">No hay casos con estos filtros.</td></tr>
              }
            </tbody>
          </table>
        </div>
        <nav class="sr-fila paginacion" aria-label="Paginación">
          <button type="button" [disabled]="p.pagina === 0" (click)="buscar(p.pagina - 1)">Anterior</button>
          <span>Página {{ p.pagina + 1 }} de {{ paginas() }}</span>
          <button type="button" [disabled]="p.pagina + 1 >= paginas()" (click)="buscar(p.pagina + 1)">Siguiente</button>
        </nav>
      } @else if (!error()) {
        <p class="sr-suave">Cargando…</p>
      }
    </section>
  `,
  styles: `.paginacion { align-items: center; margin-top: .75rem; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BandejaCasos {
  private readonly api = inject(ApiCasos);
  private readonly sesion = inject(ServicioSesion);

  protected readonly estados = ESTADOS_CASO;
  protected readonly tipos = TIPOS_SERVICIO;
  protected readonly prioridades = PRIORIDADES;
  protected readonly nombreEstado = NOMBRE_ESTADO;
  protected readonly nombreServicio = NOMBRE_SERVICIO;
  protected readonly nombrePrioridad = NOMBRE_PRIORIDAD;
  protected readonly nombreMagnitud = NOMBRE_MAGNITUD;
  protected readonly colorPrioridad = COLOR_PRIORIDAD;
  private readonly empresas = inject(Empresas);
  protected empresa(id?: string | null): string {
    return this.empresas.nombre(id);
  }

  protected readonly esOperador = computed(() => this.sesion.rol() === 'OPERADOR');
  protected readonly esAdmin = computed(() => this.sesion.rol() === 'ADMINISTRADOR');

  protected filtro: FiltroCasos = {};
  protected readonly ordenes = ORDENES_CASOS;

  /** Cambiar el orden reordena al instante, desde la primera página. */
  protected ordenar(orden: OrdenCasos): void {
    this.filtro.orden = orden === 'PRIORIDAD' ? undefined : orden;
    this.buscar(0);
  }
  /** Departamento y municipio elegidos en las listas ('' = todos). */
  protected territorio = { codigoDepartamento: '', codigoMunicipio: '' };
  protected readonly pagina = signal<Pagina<Caso> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly paginas = computed(() => {
    const p = this.pagina();
    return p ? Math.max(1, Math.ceil(p.total / p.tamano)) : 1;
  });

  constructor() {
    this.buscar(0);
  }

  protected buscar(pagina: number): void {
    this.error.set(null);
    this.api.listar({
      ...this.filtro,
      codigoDepartamento: this.territorio.codigoDepartamento || undefined,
      codigoMunicipio: this.territorio.codigoMunicipio || undefined,
      pagina,
      tamano: TAMANO,
    }).subscribe({
      next: (p) => this.pagina.set(p),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }
}

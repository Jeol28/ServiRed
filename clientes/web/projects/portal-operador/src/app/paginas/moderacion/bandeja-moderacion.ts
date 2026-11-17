import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ApiModeracion, EstadoModeracion, FechaCortaPipe, Moderacion, NOMBRE_SERVICIO, Pagina, mensajeDeError,
} from 'compartido';

export const NOMBRE_MODERACION: Record<EstadoModeracion, string> = {
  PENDIENTE: 'Pendiente', VALIDADA: 'Validada', DESCARTADA: 'Descartada',
};

/** Denuncias anónimas por moderar (RF-5). No existe ningún dato del denunciante. */
@Component({
  selector: 'app-bandeja-moderacion',
  imports: [FormsModule, RouterLink, FechaCortaPipe],
  template: `
    <h1>Denuncias anónimas</h1>
    <form class="sr-tarjeta sr-fila" (ngSubmit)="buscar(0)">
      <label>Estado
        <select name="estado" [(ngModel)]="estado">
          <option [ngValue]="undefined">Todas</option>
          @for (e of estados; track e) { <option [ngValue]="e">{{ nombreModeracion[e] }}</option> }
        </select>
      </label>
      <button class="sr-primario" type="submit">Buscar</button>
    </form>
    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }
    <section class="sr-tarjeta">
      @if (pagina(); as p) {
        <div class="sr-desplazable">
          <table class="sr-tabla">
            <thead><tr><th>Recibida</th><th>Servicio</th><th>Municipio</th><th>Estado</th><th>Descripción</th></tr></thead>
            <tbody>
              @for (d of p.elementos; track d.idDenuncia) {
                <tr>
                  <td><a [routerLink]="['/moderacion', d.idDenuncia]">{{ d.recibidaEn | fechaCorta }}</a></td>
                  <td>{{ nombreServicio[d.tipoServicio] }}</td>
                  <td>{{ d.nombreMunicipio ?? d.codigoMunicipio }}</td>
                  <td>{{ nombreModeracion[d.estado] }}</td>
                  <td class="resumen">{{ d.descripcion }}</td>
                </tr>
              } @empty {
                <tr><td colspan="5" class="sr-suave">No hay denuncias.</td></tr>
              }
            </tbody>
          </table>
        </div>
        <nav class="sr-fila paginacion" aria-label="Paginación">
          <button type="button" [disabled]="p.pagina === 0" (click)="buscar(p.pagina - 1)">Anterior</button>
          <span>Página {{ p.pagina + 1 }} de {{ paginas() }}</span>
          <button type="button" [disabled]="p.pagina + 1 >= paginas()" (click)="buscar(p.pagina + 1)">Siguiente</button>
        </nav>
      }
    </section>
  `,
  styles: `
    .resumen { max-width: 40ch; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .paginacion { align-items: center; margin-top: .75rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BandejaModeracion {
  private readonly api = inject(ApiModeracion);
  protected readonly estados: EstadoModeracion[] = ['PENDIENTE', 'VALIDADA', 'DESCARTADA'];
  protected readonly nombreModeracion = NOMBRE_MODERACION;
  protected readonly nombreServicio = NOMBRE_SERVICIO;
  protected estado: EstadoModeracion | undefined = 'PENDIENTE';
  protected readonly pagina = signal<Pagina<Moderacion> | null>(null);
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
    this.api.listar({ estado: this.estado, pagina, tamano: 20 }).subscribe({
      next: (p) => this.pagina.set(p),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }
}

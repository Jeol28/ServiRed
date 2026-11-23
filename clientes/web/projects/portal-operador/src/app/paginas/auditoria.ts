import { JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiAuditoria, CATEGORIAS_AUDITORIA, CategoriaAuditoria, FechaCortaPipe, FiltroAuditoria, Pagina,
  ROLES_ACTOR_AUDITORIA, RegistroAuditoria, ServicioSesion, TIPOS_RECURSO_AUDITORIA, VerificacionAuditoria,
  mensajeDeError,
} from 'compartido';

type Vista = 'registros' | 'alertas';

/**
 * Registro de auditoría y alertas de seguridad (RF-12, RF-13). Solo lectura:
 * el registro es de solo inserción. El administrador además verifica la
 * integridad de la cadena de hashes.
 */
@Component({
  selector: 'app-auditoria',
  imports: [FormsModule, FechaCortaPipe, JsonPipe],
  template: `
    <h1>Auditoría</h1>
    <div class="sr-fila pestanas" role="tablist">
      <button type="button" role="tab" [attr.aria-selected]="vista() === 'registros'"
              [class.sr-primario]="vista() === 'registros'" (click)="cambiar('registros')">Registro</button>
      <button type="button" role="tab" [attr.aria-selected]="vista() === 'alertas'"
              [class.sr-primario]="vista() === 'alertas'" (click)="cambiar('alertas')">Alertas</button>
      @if (esAdmin()) {
        <button type="button" class="derecha" [disabled]="verificando()" (click)="verificar()">
          {{ verificando() ? 'Verificando…' : 'Verificar integridad' }}
        </button>
      }
    </div>

    @if (verificacion(); as v) {
      <p class="sr-aviso" [class.exito]="v.integra" [class.error]="!v.integra" role="status">
        @if (v.integra) {
          Cadena íntegra: {{ v.registros }} registros verificados.
        } @else {
          ¡Cadena rota! Primer registro alterado: secuencia {{ v.primeraRota }}.
        }
      </p>
    }

    <form class="sr-tarjeta sr-fila" (ngSubmit)="buscar(0)">
      @if (vista() === 'registros') {
        <label>Categoría
          <select name="categoria" [(ngModel)]="filtro.categoria">
            <option [ngValue]="undefined">Todas</option>
            @for (c of categorias; track c) { <option [ngValue]="c">{{ c }}</option> }
          </select>
        </label>
        <!-- Quién actuó y sobre qué, sin necesidad de conocer identificadores. -->
        <label>Actor
          <select name="actorRol" [(ngModel)]="filtro.actorRol">
            <option [ngValue]="undefined">Todos</option>
            @for (r of rolesActor; track r.valor) { <option [ngValue]="r.valor">{{ r.nombre }}</option> }
          </select>
        </label>
        <label>Recurso
          <select name="recursoTipo" [(ngModel)]="filtro.recursoTipo">
            <option [ngValue]="undefined">Todos</option>
            @for (t of tiposRecurso; track t.valor) { <option [ngValue]="t.valor">{{ t.nombre }}</option> }
          </select>
        </label>
      }
      <label>Acción <input name="accion" [(ngModel)]="filtro.accion" size="18" /></label>
      <button class="sr-primario" type="submit">Buscar</button>
      @if (vista() === 'registros') {
          <details class="por-id">
            <summary>Buscar por identificador</summary>
            <div class="sr-fila">
              <label>Id del actor <input name="actor" [(ngModel)]="filtro.actorId" size="20" /></label>
              <label>Id del recurso <input name="recurso" [(ngModel)]="filtro.recursoId" size="20" /></label>
            </div>
          </details>
      }
    </form>

    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }

    <section class="sr-tarjeta">
      @if (pagina(); as p) {
        <p class="sr-suave">{{ p.total }} registros</p>
        <div class="sr-desplazable">
          <table class="sr-tabla">
            <thead>
              <tr><th>#</th><th>Fecha</th><th>Categoría</th><th>Acción</th><th>Resultado</th><th>Actor</th><th>Recurso</th><th>Origen</th></tr>
            </thead>
            <tbody>
              @for (r of p.elementos; track r.secuencia) {
                <tr (click)="abierto.set(abierto() === r.secuencia ? null : r.secuencia)" class="fila">
                  <td>{{ r.secuencia }}</td>
                  <td>{{ r.ocurridoEn | fechaCorta }}</td>
                  <td>{{ r.categoria }}</td>
                  <td><code>{{ r.accion }}</code></td>
                  <td>{{ r.resultado ?? '—' }}</td>
                  <td>{{ nombreRol(r.actorRol) }} <span class="sr-suave id">{{ r.actorId ?? '' }}</span></td>
                  <td>{{ nombreRecurso(r.recursoTipo) }} <span class="sr-suave id">{{ r.recursoId ?? '' }}</span></td>
                  <td>{{ r.origen }}</td>
                </tr>
                @if (abierto() === r.secuencia) {
                  <tr><td colspan="8"><pre>{{ { correlationId: r.correlationId, detalle: r.detalle, hash: r.hash } | json }}</pre></td></tr>
                }
              } @empty {
                <tr><td colspan="8" class="sr-suave">Sin registros.</td></tr>
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
    .pestanas { margin-bottom: 1rem; }
    .derecha { margin-left: auto; }
    .fila { cursor: pointer; }
    .id { font-size: .75rem; display: block; word-break: break-all; }
    pre { white-space: pre-wrap; word-break: break-all; margin: 0; font-size: .8rem; }
    .paginacion { align-items: center; margin-top: .75rem; }
    .por-id { flex: 1 1 100%; }
    .por-id summary { cursor: pointer; color: var(--sr-texto-suave); font-size: .9rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Auditoria {
  private readonly api = inject(ApiAuditoria);
  private readonly sesion = inject(ServicioSesion);

  protected readonly categorias: CategoriaAuditoria[] = CATEGORIAS_AUDITORIA;
  protected readonly rolesActor = ROLES_ACTOR_AUDITORIA;
  protected readonly tiposRecurso = TIPOS_RECURSO_AUDITORIA;

  protected nombreRol(rol?: string | null): string {
    return rol ? ROLES_ACTOR_AUDITORIA.find((r) => r.valor === rol)?.nombre ?? rol : 'Sistema o anónimo';
  }

  protected nombreRecurso(tipo?: string | null): string {
    return tipo ? TIPOS_RECURSO_AUDITORIA.find((t) => t.valor === tipo)?.nombre ?? tipo : '—';
  }
  protected readonly esAdmin = computed(() => this.sesion.rol() === 'ADMINISTRADOR');
  protected readonly vista = signal<Vista>('registros');
  protected filtro: FiltroAuditoria = {};
  protected readonly pagina = signal<Pagina<RegistroAuditoria> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly abierto = signal<number | null>(null);
  protected readonly verificacion = signal<VerificacionAuditoria | null>(null);
  protected readonly verificando = signal(false);
  protected readonly paginas = computed(() => {
    const p = this.pagina();
    return p ? Math.max(1, Math.ceil(p.total / p.tamano)) : 1;
  });

  constructor() {
    this.buscar(0);
  }

  protected cambiar(vista: Vista): void {
    this.vista.set(vista);
    this.filtro = {};
    this.buscar(0);
  }

  protected buscar(pagina: number): void {
    this.error.set(null);
    const limpio = Object.fromEntries(
      Object.entries(this.filtro).map(([k, v]) => [k, typeof v === 'string' ? v.trim() || undefined : v]),
    ) as FiltroAuditoria;
    const consulta = this.vista() === 'registros'
      ? this.api.registros({ ...limpio, pagina, tamano: 50 })
      : this.api.alertas({ accion: limpio.accion, pagina, tamano: 50 });
    consulta.subscribe({
      next: (p) => this.pagina.set(p),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }

  protected verificar(): void {
    this.verificando.set(true);
    this.api.integridad().subscribe({
      next: (v) => {
        this.verificando.set(false);
        this.verificacion.set(v);
      },
      error: (e: unknown) => {
        this.verificando.set(false);
        this.error.set(mensajeDeError(e));
      },
    });
  }
}

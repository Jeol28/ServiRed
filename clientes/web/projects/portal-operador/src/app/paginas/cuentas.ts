import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiCuentas, ApiEmpresas, Cuenta, EmpresaPrestadora, FechaCortaPipe, FiltroTerritorio, NOMBRE_SERVICIO,
  NuevaCuentaPersonal, Pagina, Rol, SelectorTerritorio, TIPOS_SERVICIO, TipoServicio, mensajeDeError,
} from 'compartido';
import { Empresas, filtrarPorTexto } from '../empresas';

const ROLES_PERSONAL: Rol[] = ['OPERADOR', 'REGULADOR', 'ADMINISTRADOR'];

/** Administración de cuentas (RF-11): solo el administrador crea y desactiva personal. */
@Component({
  selector: 'app-cuentas',
  imports: [FormsModule, FechaCortaPipe, SelectorTerritorio],
  template: `
    <h1>Cuentas</h1>
    <form class="sr-tarjeta" (ngSubmit)="crear()" #f="ngForm">
      <h2>Nueva cuenta de personal</h2>
      <div class="sr-fila">
        <label>Correo <input name="correo" type="email" required [(ngModel)]="nueva.correo" autocomplete="off" /></label>
        <label>Nombre <input name="nombre" maxlength="120" [(ngModel)]="nueva.nombre" /></label>
        <label>Clave inicial
          <input name="clave" type="password" required minlength="10" maxlength="64" autocomplete="new-password"
                 [(ngModel)]="nueva.clave" />
        </label>
        <label>Rol
          <select name="rol" [(ngModel)]="nueva.rol">
            @for (r of roles; track r) { <option [ngValue]="r">{{ r }}</option> }
          </select>
        </label>
        @if (nueva.rol === 'OPERADOR') {
          <!-- Encontrar la empresa sin conocer su nombre: por lo que presta y dónde (ADR-35). -->
          <fieldset class="buscar-empresa">
            <legend>Empresa del operador</legend>
            <label>Servicio
              <select name="servicioEmpresa" [ngModel]="servicioEmpresa()"
                      (ngModelChange)="servicioEmpresa.set($event); filtrarEmpresas()">
                <option value="">Todos</option>
                @for (s of servicios; track s) { <option [value]="s">{{ nombreServicio[s] }}</option> }
              </select>
            </label>
            <sr-selector-territorio [f]="territorioEmpresa" (cambio)="filtrarEmpresas()" />
            <label>Nombre o NIT
              <input name="buscarEmpresa" placeholder="Opcional" [ngModel]="busquedaEmpresa()"
                     (ngModelChange)="busquedaEmpresa.set($event)" />
            </label>
            <label>Empresa
              <select name="empresa" required [(ngModel)]="nueva.idEmpresa">
                <option [ngValue]="undefined">
                  {{ buscandoEmpresas() ? 'Buscando…' : empresasEncontradas().length + ' empresa(s): elija una' }}
                </option>
                @for (e of empresasEncontradas(); track e.idEmpresa) {
                  <option [ngValue]="e.idEmpresa">{{ e.nombre }} · NIT {{ e.nit }} · {{ etiquetaServicios(e) }}</option>
                }
              </select>
            </label>
          </fieldset>
        }
        <button class="sr-primario" type="submit" [disabled]="f.invalid || ocupado()">Crear</button>
      </div>
      <p class="sr-suave">La clave debe tener entre 10 y 64 caracteres. Un operador pertenece a una empresa.</p>
    </form>

    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }
    @if (aviso()) { <p class="sr-aviso exito" role="status">{{ aviso() }}</p> }

    <section class="sr-tarjeta">
      <form class="sr-fila" (ngSubmit)="buscar(0)" role="search" aria-label="Buscar cuentas">
        <label>Correo
          <input name="correo" type="search" [(ngModel)]="filtroCorreo" placeholder="parte del correo" size="24" />
        </label>
        <label>Rol
          <select [(ngModel)]="filtroRol" name="filtroRol" (ngModelChange)="buscar(0)">
            <option [ngValue]="undefined">Todos</option>
            @for (r of todosLosRoles; track r) { <option [ngValue]="r">{{ r }}</option> }
          </select>
        </label>
        <button type="submit">Buscar</button>
      </form>
      @if (pagina(); as p) { <p class="sr-suave">{{ p.total }} cuentas · las más recientes primero</p> }
      @if (pagina(); as p) {
        <div class="sr-desplazable">
          <table class="sr-tabla">
            <thead><tr><th>Correo</th><th>Nombre</th><th>Rol</th><th>Empresa</th><th>Creada</th><th>Estado</th></tr></thead>
            <tbody>
              @for (c of p.elementos; track c.idCuenta) {
                <tr>
                  <td>{{ c.correo }}</td>
                  <td>{{ c.nombre ?? '' }}</td>
                  <td>{{ c.rol }}</td>
                  <td>{{ c.idEmpresa ? empresa(c.idEmpresa) : '' }}</td>
                  <td>{{ c.creadaEn | fechaCorta }}</td>
                  <td>
                    <button type="button" [class.sr-peligro]="c.activa" [disabled]="ocupado()" (click)="alternar(c)">
                      {{ c.activa ? 'Desactivar' : 'Activar' }}
                    </button>
                  </td>
                </tr>
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
    .paginacion { align-items: center; margin-top: .75rem; }
    /* Ocupa toda la fila del formulario; adentro, los filtros se acomodan en columnas. */
    .buscar-empresa {
      flex: 1 1 100%; display: grid; gap: .5rem .75rem; margin: 0; padding: .75rem;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr));
      border: 1px solid var(--sr-borde); border-radius: var(--sr-radio);
    }
    .buscar-empresa legend { padding: 0 .25rem; font-weight: 600; }
    .buscar-empresa label:last-child { grid-column: 1 / -1; }
    .buscar-empresa select { width: 100%; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Cuentas {
  private readonly api = inject(ApiCuentas);
  protected readonly roles = ROLES_PERSONAL;
  protected readonly todosLosRoles: Rol[] = ['CIUDADANO', ...ROLES_PERSONAL];
  private readonly empresas = inject(Empresas);
  private readonly apiEmpresas = inject(ApiEmpresas);
  protected readonly servicios = TIPOS_SERVICIO;
  protected readonly nombreServicio = NOMBRE_SERVICIO;
  /**
   * Hay unas 1800 empresas reales (ADR-35). Se filtran por servicio, departamento y municipio
   * (las dueñas de alguna zona allí) y, encima, por nombre o NIT.
   */
  protected readonly servicioEmpresa = signal<TipoServicio | ''>('');
  protected readonly territorioEmpresa: FiltroTerritorio = { codigoDepartamento: '', codigoMunicipio: '' };
  protected readonly busquedaEmpresa = signal('');
  protected readonly buscandoEmpresas = signal(false);
  /** null = sin filtro de servicio ni territorio: todo el catálogo. */
  private readonly candidatas = signal<EmpresaPrestadora[] | null>(null);
  private consulta = 0;
  protected readonly empresasEncontradas = computed(() =>
    filtrarPorTexto(this.candidatas() ?? this.empresas.todas(), this.busquedaEmpresa(), 100));

  protected filtrarEmpresas(): void {
    const filtro = {
      tipoServicio: this.servicioEmpresa() || undefined,
      codigoDepartamento: this.territorioEmpresa.codigoDepartamento || undefined,
      codigoMunicipio: this.territorioEmpresa.codigoMunicipio || undefined,
    };
    const n = ++this.consulta;
    if (!filtro.tipoServicio && !filtro.codigoDepartamento && !filtro.codigoMunicipio) {
      this.candidatas.set(null);
      this.buscandoEmpresas.set(false);
      return;
    }
    this.buscandoEmpresas.set(true);
    this.apiEmpresas.listar(filtro).subscribe({
      // Solo cuenta la respuesta de la última consulta (el usuario puede cambiar rápido).
      next: (l) => {
        if (n === this.consulta) {
          this.candidatas.set(l);
          this.buscandoEmpresas.set(false);
          if (this.nueva.idEmpresa && !l.some((e) => e.idEmpresa === this.nueva.idEmpresa)) {
            this.nueva.idEmpresa = undefined;
          }
        }
      },
      error: (e: unknown) => {
        if (n === this.consulta) {
          this.buscandoEmpresas.set(false);
          this.error.set(mensajeDeError(e));
        }
      },
    });
  }

  protected etiquetaServicios(e: EmpresaPrestadora): string {
    return e.servicios.map((s) => this.nombreServicio[s]).join(', ');
  }
  protected nueva: NuevaCuentaPersonal = this.vacia();
  protected filtroRol: Rol | undefined = undefined;
  protected filtroCorreo = '';
  protected readonly pagina = signal<Pagina<Cuenta> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  protected readonly ocupado = signal(false);
  protected readonly paginas = computed(() => {
    const p = this.pagina();
    return p ? Math.max(1, Math.ceil(p.total / p.tamano)) : 1;
  });

  constructor() {
    this.empresas.cargar();
    this.buscar(0);
  }

  protected empresa(id?: string | null): string {
    return this.empresas.nombre(id);
  }

  protected buscar(pagina: number): void {
    this.api.listar({ rol: this.filtroRol, correo: this.filtroCorreo.trim() || undefined, pagina, tamano: 20 }).subscribe({
      next: (p) => this.pagina.set(p),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }

  protected crear(): void {
    const cuenta: NuevaCuentaPersonal = {
      ...this.nueva,
      correo: this.nueva.correo.trim(),
      nombre: this.nueva.nombre?.trim() || undefined,
      idEmpresa: this.nueva.rol === 'OPERADOR' ? this.nueva.idEmpresa : undefined,
    };
    this.ejecutar(this.api.crear(cuenta), `Cuenta ${cuenta.correo} creada`, () => (this.nueva = this.vacia()));
  }

  protected alternar(c: Cuenta): void {
    this.ejecutar(this.api.cambiar(c.idCuenta, { activa: !c.activa }),
      c.activa ? `Cuenta ${c.correo} desactivada` : `Cuenta ${c.correo} activada`);
  }

  private ejecutar(accion: ReturnType<ApiCuentas['crear']>, exito: string, despues?: () => void): void {
    this.ocupado.set(true);
    this.error.set(null);
    this.aviso.set(null);
    accion.subscribe({
      next: () => {
        this.ocupado.set(false);
        this.aviso.set(exito);
        despues?.();
        this.buscar(this.pagina()?.pagina ?? 0);
      },
      error: (e: unknown) => {
        this.ocupado.set(false);
        this.error.set(mensajeDeError(e));
      },
    });
  }

  private vacia(): NuevaCuentaPersonal {
    return { correo: '', clave: '', nombre: '', rol: 'OPERADOR', idEmpresa: undefined };
  }
}

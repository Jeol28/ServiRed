import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiCuentas, Cuenta, FechaCortaPipe, NuevaCuentaPersonal, Pagina, Rol, mensajeDeError,
} from 'compartido';
import { EMPRESAS, nombreEmpresa } from '../empresas';

const ROLES_PERSONAL: Rol[] = ['OPERADOR', 'REGULADOR', 'ADMINISTRADOR'];

/** Administración de cuentas (RF-11): solo el administrador crea y desactiva personal. */
@Component({
  selector: 'app-cuentas',
  imports: [FormsModule, FechaCortaPipe],
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
          <label>Empresa
            <select name="empresa" required [(ngModel)]="nueva.idEmpresa">
              @for (e of empresas; track e.id) { <option [ngValue]="e.id">{{ e.nombre }}</option> }
            </select>
          </label>
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
  styles: `.paginacion { align-items: center; margin-top: .75rem; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Cuentas {
  private readonly api = inject(ApiCuentas);
  protected readonly roles = ROLES_PERSONAL;
  protected readonly todosLosRoles: Rol[] = ['CIUDADANO', ...ROLES_PERSONAL];
  protected readonly empresas = EMPRESAS;
  protected readonly empresa = nombreEmpresa;
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
    this.buscar(0);
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
    return { correo: '', clave: '', nombre: '', rol: 'OPERADOR', idEmpresa: EMPRESAS[0].id };
  }
}

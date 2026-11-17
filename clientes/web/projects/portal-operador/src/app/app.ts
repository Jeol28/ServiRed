import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Rol, ServicioSesion } from 'compartido';

interface Enlace {
  ruta: string;
  texto: string;
  roles: Rol[];
}

const ENLACES: Enlace[] = [
  { ruta: '/casos', texto: 'Casos', roles: ['OPERADOR', 'REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/mapa', texto: 'Mapa', roles: ['OPERADOR', 'REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/moderacion', texto: 'Denuncias', roles: ['OPERADOR', 'REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/exportacion', texto: 'Exportación', roles: ['REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/auditoria', texto: 'Auditoría', roles: ['REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/cuentas', texto: 'Cuentas', roles: ['ADMINISTRADOR'] },
];

const NOMBRE_ROL: Record<Rol, string> = {
  CIUDADANO: 'Ciudadano', OPERADOR: 'Operador', REGULADOR: 'Regulador', ADMINISTRADOR: 'Administrador',
};

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    @if (sesion.autenticado()) {
      <header class="sr-barra">
        <a class="marca" routerLink="/">SERVI-RED</a>
        <nav aria-label="Secciones">
          @for (e of enlaces(); track e.ruta) {
            <a [routerLink]="e.ruta" routerLinkActive="activo" ariaCurrentWhenActive="page">{{ e.texto }}</a>
          }
        </nav>
        <div class="derecha">
          <span class="sr-suave sr-ocultar-movil">{{ nombreRol() }}</span>
          <button type="button" (click)="salir()">Cerrar sesión</button>
        </div>
      </header>
    }
    <main class="sr-contenedor">
      <router-outlet />
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly sesion = inject(ServicioSesion);
  private readonly router = inject(Router);

  protected readonly enlaces = computed(() => {
    const rol = this.sesion.rol();
    return ENLACES.filter((e) => rol !== null && e.roles.includes(rol));
  });

  protected readonly nombreRol = computed(() => {
    const rol = this.sesion.rol();
    return rol ? NOMBRE_ROL[rol] : '';
  });

  protected salir(): void {
    this.sesion.cerrar().subscribe({
      complete: () => void this.router.navigateByUrl('/ingreso'),
      error: () => void this.router.navigateByUrl('/ingreso'),
    });
  }
}

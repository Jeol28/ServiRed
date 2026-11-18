import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { EnlaceMenu, MenuLateral, Rol, ServicioSesion } from 'compartido';
import { filter, map } from 'rxjs';

interface Enlace extends EnlaceMenu {
  roles: Rol[];
}

const ENLACES: Enlace[] = [
  { ruta: '/casos', texto: 'Casos', icono: '📋', roles: ['OPERADOR', 'REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/mapa', texto: 'Mapa', icono: '🗺️', roles: ['OPERADOR', 'REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/moderacion', texto: 'Denuncias', icono: '🚩', roles: ['OPERADOR', 'REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/exportacion', texto: 'Exportación', icono: '⬇️', roles: ['REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/auditoria', texto: 'Auditoría', icono: '🛡️', roles: ['REGULADOR', 'ADMINISTRADOR'] },
  { ruta: '/cuentas', texto: 'Cuentas', icono: '👥', roles: ['ADMINISTRADOR'] },
];

const NOMBRE_ROL: Record<Rol, string> = {
  CIUDADANO: 'Ciudadano', OPERADOR: 'Operador', REGULADOR: 'Regulador', ADMINISTRADOR: 'Administrador',
};

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, MenuLateral],
  template: `
    @if (sesion.autenticado()) {
      <header class="sr-barra-flotante">
        <button type="button" class="sr-redondo" aria-label="Abrir menú" aria-controls="menu-lateral"
                [attr.aria-expanded]="menu()" (click)="menu.set(true)">☰</button>
        <a class="sr-pildora" routerLink="/">
          <span class="marca">SERVI-RED · Operación</span>
          <span class="sub">{{ seccion() }}</span>
        </a>
        <div class="derecha">
          <span class="sr-flotante rol sr-ocultar-movil">{{ nombreRol() }}</span>
        </div>
      </header>
      <sr-menu-lateral [abierto]="menu()" (cerrar)="menu.set(false)" [enlaces]="enlaces()"
                       [subtitulo]="'Portal de operación · ' + nombreRol()">
        <button type="button" (click)="salir()">↩ Cerrar sesión</button>
        <a href="/">Ir al portal público</a>
      </sr-menu-lateral>
    }
    <main [class.sr-contenedor]="!enMapa()" [class.sr-bajo-barra]="sesion.autenticado() && !enMapa()">
      <router-outlet />
    </main>
  `,
  styles: `
    .rol { border-radius: 999px; padding: .55rem 1rem; font-size: .9rem; font-weight: 600; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly sesion = inject(ServicioSesion);
  private readonly router = inject(Router);
  protected readonly menu = signal(false);

  private readonly ruta = toSignal(
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd), map((e) => e.urlAfterRedirects.split('?')[0])),
    { initialValue: '' },
  );
  /** El mapa operativo ocupa toda la ventana, como en las apps. */
  protected readonly enMapa = computed(() => this.sesion.autenticado() && this.ruta() === '/mapa');
  protected readonly seccion = computed(() => {
    const r = this.ruta();
    return ENLACES.find((e) => r === e.ruta || r.startsWith(e.ruta + '/'))?.texto ?? 'Portal de operación';
  });

  protected readonly enlaces = computed(() => {
    const rol = this.sesion.rol();
    return ENLACES.filter((e) => rol !== null && e.roles.includes(rol));
  });

  protected readonly nombreRol = computed(() => {
    const rol = this.sesion.rol();
    return rol ? NOMBRE_ROL[rol] : '';
  });

  protected salir(): void {
    this.menu.set(false);
    this.sesion.cerrar().subscribe({
      complete: () => void this.router.navigateByUrl('/ingreso'),
      error: () => void this.router.navigateByUrl('/ingreso'),
    });
  }
}

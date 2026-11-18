import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { EnlaceMenu, MenuLateral } from 'compartido';
import { filter, map } from 'rxjs';

const ENLACES: EnlaceMenu[] = [
  { ruta: '/', texto: 'Mapa', icono: '🗺️', exacto: true },
  { ruta: '/indicadores', texto: 'Indicadores', icono: '📊' },
  { ruta: '/datos-abiertos', texto: 'Datos abiertos', icono: '⬇️' },
];

const SUBTITULO: Record<string, string> = {
  '/indicadores': 'Indicadores',
  '/datos-abiertos': 'Datos abiertos',
};

/**
 * Estilo de las apps: la barra flotante (☰, marca, acceso del personal) sobre
 * el mapa a pantalla completa; las demás secciones están en el menú lateral.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, MenuLateral],
  template: `
    <header class="sr-barra-flotante">
      <button type="button" class="sr-redondo" aria-label="Abrir menú" aria-controls="menu-lateral"
              [attr.aria-expanded]="menu()" (click)="menu.set(true)"><span class="sr-hamburguesa" aria-hidden="true"></span></button>
      <a class="sr-pildora" routerLink="/">
        <span class="marca">SERVI-RED</span>
        <span class="sub">{{ subtitulo() }}</span>
      </a>
      <!-- El portal de operación está en la misma dirección y pide inicio de sesión. -->
      <div class="derecha">
        <a class="sr-boton sr-flotante" href="/operacion/" title="Acceso del personal">
          <span class="sr-ocultar-movil">Acceso del personal</span><span class="solo-movil" aria-hidden="true">👤</span>
        </a>
      </div>
    </header>
    <sr-menu-lateral [abierto]="menu()" (cerrar)="menu.set(false)" [enlaces]="enlaces"
                     subtitulo="Cortes de servicios públicos en Colombia">
      <p class="sr-suave">Para reportar un corte o hacer una denuncia anónima, use la app SERVI-RED en su teléfono.</p>
      <a class="sr-boton" href="/operacion/">👤 Acceso del personal</a>
      <p class="sr-suave">
        Datos agregados y anonimizados: no se publica ningún reporte individual ni dato personal.
        Mapa © colaboradores de OpenStreetMap.
      </p>
    </sr-menu-lateral>
    <main [class.sr-contenedor]="!enMapa()" [class.sr-bajo-barra]="!enMapa()">
      <router-outlet />
    </main>
  `,
  styles: `
    .solo-movil { display: none; font-size: 1.2rem; }
    @media (max-width: 600px) {
      .solo-movil { display: inline; }
      .derecha .sr-boton { width: 48px; padding: 0 !important; justify-content: center; }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  private readonly router = inject(Router);
  protected readonly enlaces = ENLACES;
  protected readonly menu = signal(false);

  private readonly ruta = toSignal(
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd), map((e) => e.urlAfterRedirects.split('?')[0])),
    { initialValue: '/' },
  );
  protected readonly enMapa = computed(() => !(this.ruta() in SUBTITULO));
  protected readonly subtitulo = computed(() => SUBTITULO[this.ruta()] ?? 'Cortes de servicios en Colombia');
}

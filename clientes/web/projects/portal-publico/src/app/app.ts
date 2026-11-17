import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="sr-barra">
      <a class="marca" routerLink="/">SERVI-RED</a>
      <nav aria-label="Secciones">
        <a routerLink="/" routerLinkActive="activo" [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page">Mapa</a>
        <a routerLink="/indicadores" routerLinkActive="activo" ariaCurrentWhenActive="page">Indicadores</a>
        <a routerLink="/datos-abiertos" routerLinkActive="activo" ariaCurrentWhenActive="page">Datos abiertos</a>
        <a routerLink="/denunciar" routerLinkActive="activo" ariaCurrentWhenActive="page">Denuncia anónima</a>
      </nav>
      <!-- El portal de operación está en la misma dirección y pide inicio de sesión. -->
      <div class="derecha"><a class="sr-boton" href="/operacion/">Acceso del personal</a></div>
    </header>
    <main class="sr-contenedor">
      <router-outlet />
    </main>
    <footer class="sr-contenedor sr-suave pie">
      Datos agregados y anonimizados: no se publica ningún reporte individual ni dato personal.
      Mapa © colaboradores de OpenStreetMap.
    </footer>
  `,
  styles: `.pie { font-size: .85rem; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}

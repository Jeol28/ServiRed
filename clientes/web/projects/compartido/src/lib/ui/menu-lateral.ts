import { ChangeDetectionStrategy, Component, ElementRef, effect, input, output, viewChild } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

export interface EnlaceMenu {
  ruta: string;
  texto: string;
  /** Emoji decorativo (no lo leen los lectores de pantalla). */
  icono: string;
  /** Activo solo si la ruta coincide exactamente (para la raíz). */
  exacto?: boolean;
}

/**
 * Menú lateral (☰) que se desliza desde la izquierda sobre el contenido, igual
 * que en las apps. Cerrado queda `inert`: no recibe foco ni lo leen los lectores
 * de pantalla. Se cierra con Escape, con el velo o al elegir una sección.
 */
@Component({
  selector: 'sr-menu-lateral',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <div class="velo" [class.abierto]="abierto()" (click)="cerrar.emit()"></div>
    <aside #cajon id="menu-lateral" class="cajon" [class.abierto]="abierto()" [attr.inert]="abierto() ? null : ''"
           aria-label="Menú">
      <div class="encabezado">
        <span class="marca">SERVI-RED</span>
        <span class="sr-suave">{{ subtitulo() }}</span>
      </div>
      <nav aria-label="Secciones">
        @for (e of enlaces(); track e.ruta) {
          <a [routerLink]="e.ruta" routerLinkActive="activo" [routerLinkActiveOptions]="{ exact: e.exacto ?? false }"
             ariaCurrentWhenActive="page" (click)="cerrar.emit()">
            <span class="icono" aria-hidden="true">{{ e.icono }}</span><span class="texto">{{ e.texto }}</span>
          </a>
        }
      </nav>
      <div class="pie"><ng-content /></div>
    </aside>
  `,
  styles: `
    .velo {
      position: fixed; inset: 0; z-index: 1200; background: rgb(0 0 0 / 35%);
      opacity: 0; pointer-events: none; transition: opacity .2s;
    }
    .velo.abierto { opacity: 1; pointer-events: auto; }
    .cajon {
      position: fixed; inset: 0 auto 0 0; z-index: 1201; width: min(320px, 86vw);
      display: flex; flex-direction: column; gap: .25rem; overflow-y: auto;
      background: var(--sr-superficie); box-shadow: 4px 0 24px rgb(0 0 0 / 25%);
      border-radius: 0 var(--sr-radio-grande) var(--sr-radio-grande) 0;
      padding: 1.5rem 1rem; transform: translateX(-105%); transition: transform .22s ease-out;
    }
    .cajon.abierto { transform: none; }
    .encabezado { display: flex; flex-direction: column; padding: 0 .75rem 1rem; border-bottom: 1px solid var(--sr-borde); margin-bottom: .5rem; }
    .marca { font-size: 1.5rem; font-weight: 800; color: var(--sr-primario); }
    nav { display: flex; flex-direction: column; gap: .2rem; }
    nav a {
      display: flex; align-items: center; gap: .9rem; padding: .8rem .9rem; border-radius: 999px;
      color: var(--sr-texto); text-decoration: none; font-weight: 500;
    }
    nav a:hover { background: color-mix(in srgb, var(--sr-primario) 8%, transparent); }
    nav a.activo { background: color-mix(in srgb, var(--sr-primario) 16%, transparent); color: var(--sr-primario); font-weight: 600; }
    .icono { width: 1.5rem; text-align: center; font-size: 1.15rem; }
    .pie { margin-top: auto; padding: 1rem .75rem 0; display: flex; flex-direction: column; gap: .75rem; font-size: .88rem; }
    @media (prefers-reduced-motion: reduce) { .velo, .cajon { transition: none; } }
  `,
  host: { '(document:keydown.escape)': 'abierto() && cerrar.emit()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MenuLateral {
  readonly abierto = input(false);
  readonly enlaces = input.required<EnlaceMenu[]>();
  readonly subtitulo = input('');
  readonly cerrar = output<void>();

  private readonly cajon = viewChild.required<ElementRef<HTMLElement>>('cajon');

  constructor() {
    // Al abrir, el foco pasa a la primera sección.
    effect(() => {
      if (this.abierto()) {
        setTimeout(() => this.cajon().nativeElement.querySelector<HTMLElement>('nav a')?.focus());
      }
    });
  }
}

import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-sin-permiso',
  imports: [RouterLink],
  template: `
    <section class="sr-tarjeta">
      <h1>Sin permiso</h1>
      <p>Su rol no tiene acceso a esta sección.</p>
      <a routerLink="/">Volver al inicio</a>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SinPermiso {}

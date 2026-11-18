import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ConjuntoIndicadores, DuracionPipe } from 'compartido';

/** Tarjeta de indicadores. Un conjunto suprimido (menos de 3 reportes) no muestra valores. */
@Component({
  selector: 'app-conjunto',
  imports: [DecimalPipe, DuracionPipe],
  template: `
    <h3>{{ titulo() }}</h3>
    @if (c(); as c) {
      @if (c.suprimido) {
        <p class="sr-suave">Datos insuficientes para publicar (menos de 3 reportes), para proteger la privacidad.</p>
      } @else {
        <dl>
          <dt>Reportes</dt><dd class="sr-cifra">{{ c.reportes ?? 0 }}</dd>
          <dt>Duración mediana</dt><dd>{{ c.duracion?.mediana | duracion }}</dd>
          <dt>Duración promedio</dt><dd>{{ c.duracion?.promedio | duracion }}</dd>
          <dt>Índice de severidad</dt><dd>{{ c.severidad?.indice ?? '—' }}</dd>
          <dt>Validados por la comunidad</dt>
          <dd>{{ c.severidad?.porcentajeValidados != null ? (c.severidad!.porcentajeValidados | number: '1.0-1') + ' %' : '—' }}</dd>
          <dt>Resueltos</dt>
          <dd>{{ c.atencion?.porcentajeResueltos != null ? (c.atencion!.porcentajeResueltos | number: '1.0-1') + ' %' : '—' }}</dd>
          <dt>Tiempo de resolución</dt><dd>{{ c.atencion?.tiempoPromedioResolucionMinutos | duracion }}</dd>
        </dl>
      }
    }
  `,
  styles: `
    :host { display: block; }
    dl { display: grid; grid-template-columns: 1fr auto; gap: .25rem .75rem; margin: 0; align-items: baseline; }
    dt { color: var(--sr-texto-suave); }
    dd { margin: 0; text-align: right; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Conjunto {
  readonly titulo = input.required<string>();
  readonly c = input.required<ConjuntoIndicadores>();
}

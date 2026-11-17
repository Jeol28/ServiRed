import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { FechaCortaPipe, Metadatos } from 'compartido';

/** Metadatos de publicación (RNF-DAT4): fuente, periodo, cobertura, anonimización. */
@Component({
  selector: 'app-metadatos',
  imports: [FechaCortaPipe],
  template: `
    <details class="sr-tarjeta">
      <summary>Acerca de estos datos</summary>
      @if (m(); as m) {
        <p><strong>Fuente:</strong> {{ m.fuente }}</p>
        <p><strong>Periodo:</strong> {{ m.periodo.desde | fechaCorta }} — {{ m.periodo.hasta | fechaCorta }}</p>
        <p><strong>Cobertura:</strong> {{ m.cobertura.reportesEnVista }} reportes en {{ m.cobertura.municipiosConReportes }} municipios.</p>
        @if (m.actualizadoEn) { <p><strong>Actualizado:</strong> {{ m.actualizadoEn | fechaCorta }}</p> }
        <p><strong>Anonimización:</strong> {{ m.anonimizacion }}</p>
        <h4>Definiciones</h4>
        <ul>
          @for (i of m.indicadores; track i.id) {
            <li><strong>{{ i.nombre }}</strong> ({{ i.unidad }}): {{ i.definicion }}</li>
          }
        </ul>
      }
    </details>
  `,
  styles: `summary { cursor: pointer; font-weight: 600; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetadatosPublicacion {
  readonly m = input.required<Metadatos>();
}

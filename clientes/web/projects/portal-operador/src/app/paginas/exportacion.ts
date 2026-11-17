import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiIndicadores, NOMBRE_SERVICIO, TIPOS_SERVICIO, descargar, filtroDePeriodo, formularioPeriodo, mensajeDeError,
} from 'compartido';

/**
 * Exportación regulatoria (RF-10): reportes con detalle operativo para el
 * regulador. Sin datos del ciudadano; cada exportación queda en la auditoría.
 */
@Component({
  selector: 'app-exportacion',
  imports: [FormsModule],
  template: `
    <h1>Exportación regulatoria</h1>
    <form class="sr-tarjeta" (ngSubmit)="exportar()">
      <p class="sr-suave">Periodo máximo de 366 días. La exportación queda registrada en la auditoría con su cuenta.</p>
      <div class="sr-fila">
        <label>Desde <input type="date" name="desde" [(ngModel)]="f.desde" required /></label>
        <label>Hasta <input type="date" name="hasta" [(ngModel)]="f.hasta" required /></label>
        <label>Servicio
          <select name="tipo" [(ngModel)]="f.tipoServicio">
            <option [ngValue]="undefined">Todos</option>
            @for (t of tipos; track t) { <option [ngValue]="t">{{ nombreServicio[t] }}</option> }
          </select>
        </label>
        <label>Departamento <input name="dep" [(ngModel)]="f.codigoDepartamento" maxlength="2" size="4" placeholder="11" /></label>
        <label>Municipio <input name="mun" [(ngModel)]="f.codigoMunicipio" maxlength="5" size="7" placeholder="11001" /></label>
        <label>Formato
          <select name="formato" [(ngModel)]="formato">
            <option ngValue="csv">CSV</option>
            <option ngValue="json">JSON</option>
          </select>
        </label>
        <button class="sr-primario" type="submit" [disabled]="ocupado()">{{ ocupado() ? 'Generando…' : 'Descargar' }}</button>
      </div>
    </form>
    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }
    @if (aviso()) { <p class="sr-aviso exito" role="status">{{ aviso() }}</p> }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Exportacion {
  private readonly api = inject(ApiIndicadores);
  protected readonly tipos = TIPOS_SERVICIO;
  protected readonly nombreServicio = NOMBRE_SERVICIO;
  protected f = formularioPeriodo(30);
  protected formato: 'csv' | 'json' = 'csv';
  protected readonly ocupado = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);

  protected exportar(): void {
    this.ocupado.set(true);
    this.error.set(null);
    this.aviso.set(null);
    const formato = this.formato;
    this.api.exportarReportes(filtroDePeriodo(this.f), formato).subscribe({
      next: (blob) => {
        this.ocupado.set(false);
        descargar(blob, `servired-reportes-${this.f.desde}-a-${this.f.hasta}.${formato}`);
        this.aviso.set('Exportación descargada.');
      },
      error: (e: unknown) => {
        this.ocupado.set(false);
        this.error.set(mensajeDeError(e));
      },
    });
  }
}

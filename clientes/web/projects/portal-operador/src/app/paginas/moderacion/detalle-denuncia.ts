import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ApiModeracion, COLOR_SERVICIO, FechaCortaPipe, MarcaMapa, Mapa, Moderacion, NOMBRE_SERVICIO, ServicioSesion,
  mensajeDeError,
} from 'compartido';
import { NOMBRE_MODERACION } from './bandeja-moderacion';

@Component({
  selector: 'app-detalle-denuncia',
  imports: [FormsModule, RouterLink, FechaCortaPipe, Mapa],
  template: `
    <p><a routerLink="/moderacion">← Denuncias</a></p>
    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }
    @if (aviso()) { <p class="sr-aviso exito" role="status">{{ aviso() }}</p> }
    @if (denuncia(); as d) {
      <h1>Denuncia · {{ nombreServicio[d.tipoServicio] }}</h1>
      <p class="sr-aviso info">Denuncia anónima: el sistema no guarda ningún dato de quien la envió.</p>
      <div class="sr-rejilla">
        <section class="sr-tarjeta">
          <p><strong>Estado:</strong> {{ nombreModeracion[d.estado] }}</p>
          <p><strong>Recibida:</strong> {{ d.recibidaEn | fechaCorta }}</p>
          <p><strong>Municipio:</strong> {{ d.nombreMunicipio ?? d.codigoMunicipio }}</p>
          <p class="descripcion">{{ d.descripcion }}</p>
          @if (d.nota) { <p><strong>Nota de moderación:</strong> {{ d.nota }} ({{ d.moderadaEn | fechaCorta }})</p> }
          @for (id of d.evidencias; track id) {
            @if (fotos()[id]; as url) {
              <img [src]="url" alt="Foto adjunta a la denuncia" class="foto" />
            } @else {
              <button type="button" (click)="verFoto(d.idDenuncia, id)">Ver foto</button>
            }
          }
        </section>
        <section class="sr-tarjeta">
          <sr-mapa [marcas]="marcas()" [centro]="[d.latitud, d.longitud]" [zoom]="15" alto="300px" titulo="Ubicación de la denuncia" />
        </section>
      </div>
      @if (d.estado === 'PENDIENTE' && puedeModerar()) {
        <form class="sr-tarjeta" (ngSubmit)="decidir('VALIDADA')" #f="ngForm">
          <h2>Decisión</h2>
          <label>Nota (obligatoria, mínimo 5 caracteres)
            <textarea name="nota" [(ngModel)]="nota" required minlength="5" maxlength="1000"></textarea>
          </label>
          <div class="sr-fila acciones">
            <button class="sr-primario" type="submit" [disabled]="f.invalid || ocupado()">Validar</button>
            <button class="sr-peligro" type="button" [disabled]="f.invalid || ocupado()" (click)="decidir('DESCARTADA')">Descartar</button>
          </div>
        </form>
      }
    }
  `,
  styles: `
    .descripcion { white-space: pre-line; }
    .foto { max-width: 100%; border-radius: 6px; display: block; margin-top: .5rem; }
    .acciones { margin-top: .75rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetalleDenuncia {
  private readonly api = inject(ApiModeracion);
  private readonly sesion = inject(ServicioSesion);
  readonly idDenuncia = input.required<string>();

  protected readonly nombreServicio = NOMBRE_SERVICIO;
  protected readonly nombreModeracion = NOMBRE_MODERACION;
  protected readonly denuncia = signal<Moderacion | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  protected readonly ocupado = signal(false);
  protected readonly fotos = signal<Record<string, string>>({});
  protected nota = '';

  protected readonly puedeModerar = computed(() => this.sesion.tieneRol('OPERADOR', 'ADMINISTRADOR'));
  protected readonly marcas = computed<MarcaMapa[]>(() => {
    const d = this.denuncia();
    return d ? [{ latitud: d.latitud, longitud: d.longitud, color: COLOR_SERVICIO[d.tipoServicio], radio: 10 }] : [];
  });

  constructor() {
    effect(() => this.cargar(this.idDenuncia()));
    inject(DestroyRef).onDestroy(() => Object.values(this.fotos()).forEach((u) => URL.revokeObjectURL(u)));
  }

  protected decidir(decision: 'VALIDADA' | 'DESCARTADA'): void {
    this.ocupado.set(true);
    this.error.set(null);
    this.api.decidir(this.idDenuncia(), decision, this.nota.trim()).subscribe({
      next: (d) => {
        this.ocupado.set(false);
        this.denuncia.set(d);
        this.aviso.set(decision === 'VALIDADA' ? 'Denuncia validada' : 'Denuncia descartada');
      },
      error: (e: unknown) => {
        this.ocupado.set(false);
        this.error.set(mensajeDeError(e));
      },
    });
  }

  protected verFoto(idDenuncia: string, idEvidencia: string): void {
    this.api.evidencia(idDenuncia, idEvidencia).subscribe({
      next: (b) => this.fotos.update((f) => ({ ...f, [idEvidencia]: URL.createObjectURL(b) })),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }

  private cargar(id: string): void {
    this.api.obtener(id).subscribe({
      next: (d) => this.denuncia.set(d),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }
}

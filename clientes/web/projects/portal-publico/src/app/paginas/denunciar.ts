import { ChangeDetectionStrategy, Component, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiDenuncias, DenunciaRecibida, Mapa, NOMBRE_SERVICIO, TIPOS_SERVICIO, TipoServicio, mensajeDeError,
} from 'compartido';

const MAXIMO_FOTO = 5 * 1024 * 1024;
const TIPOS_FOTO = ['image/jpeg', 'image/png'];

/**
 * Denuncia anónima (RF-5, RNF-SEG4). No pide ni envía ningún dato de quien
 * denuncia: sin cuenta, sin token, sin correlationId del navegador; la foto
 * se envía con un nombre neutro y el servidor elimina sus metadatos (EXIF).
 */
@Component({
  selector: 'app-denunciar',
  imports: [FormsModule, Mapa],
  template: `
    <h1>Denuncia anónima</h1>
    <p class="sr-aviso info">
      No necesita cuenta. SERVI-RED no guarda su nombre, correo, dirección IP ni ningún otro dato que lo identifique.
      La foto se publica a los moderadores sin los metadatos de la cámara (ubicación GPS, modelo, fecha).
      Para conservar el anonimato, no escriba datos personales en la descripción.
    </p>

    @if (recibida(); as r) {
      <section class="sr-tarjeta" role="status">
        <h2>Denuncia recibida</h2>
        <p>{{ r.mensaje ?? 'Gracias. Un moderador la revisará.' }}</p>
        <p class="sr-suave">No se le asigna ningún número de seguimiento para no vincularla con usted.</p>
        <button type="button" (click)="nueva()">Enviar otra denuncia</button>
      </section>
    } @else {
      <form class="sr-tarjeta formulario" (ngSubmit)="enviar()" #f="ngForm">
        <label>Servicio afectado
          <select name="tipo" required [(ngModel)]="tipoServicio">
            @for (t of tipos; track t) { <option [ngValue]="t">{{ nombreServicio[t] }}</option> }
          </select>
        </label>

        <div>
          <p id="ayuda-ubicacion">Ubicación: haga clic en el mapa donde ocurre el hecho.</p>
          <button type="button" (click)="usarMiUbicacion()">Usar mi ubicación actual</button>
          <sr-mapa #mapa [seleccionable]="true" [seleccion]="ubicacion()" alto="340px"
                   titulo="Elegir la ubicación de la denuncia" (ubicacionElegida)="elegir($event)" />
          @if (ubicacion(); as u) {
            <p class="sr-suave">Ubicación elegida: {{ u[0] }}, {{ u[1] }}</p>
          }
        </div>

        <label>Descripción (10 a 2000 caracteres)
          <textarea name="descripcion" required minlength="10" maxlength="2000" rows="5" [(ngModel)]="descripcion"
                    placeholder="Qué ocurre y desde cuándo, sin datos personales"></textarea>
        </label>

        <label>Foto (opcional, JPEG o PNG de hasta 5 MB)
          <input type="file" accept="image/jpeg,image/png" (change)="elegirFoto($event)" />
        </label>

        @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }
        <button class="sr-primario" type="submit" [disabled]="f.invalid || !ubicacion() || enviando()">
          {{ enviando() ? 'Enviando…' : 'Enviar denuncia' }}
        </button>
      </form>
    }
  `,
  styles: `.formulario { display: flex; flex-direction: column; gap: 1rem; max-width: 760px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Denunciar {
  private readonly api = inject(ApiDenuncias);
  private readonly mapa = viewChild<Mapa>('mapa');

  protected readonly tipos = TIPOS_SERVICIO;
  protected readonly nombreServicio = NOMBRE_SERVICIO;
  protected tipoServicio: TipoServicio = 'ENERGIA';
  protected descripcion = '';
  protected readonly ubicacion = signal<[number, number] | null>(null);
  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly recibida = signal<DenunciaRecibida | null>(null);
  private foto: File | null = null;

  protected elegir(u: { latitud: number; longitud: number }): void {
    this.ubicacion.set([u.latitud, u.longitud]);
  }

  /** La ubicación del navegador solo se usa para centrar el mapa y prellenar el punto. */
  protected usarMiUbicacion(): void {
    if (!('geolocation' in navigator)) {
      this.error.set('Su navegador no permite obtener la ubicación. Elíjala en el mapa.');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) => {
        const lat = Math.round(p.coords.latitude * 1e5) / 1e5;
        const lon = Math.round(p.coords.longitude * 1e5) / 1e5;
        this.ubicacion.set([lat, lon]);
        this.mapa()?.irA(lat, lon);
      },
      () => this.error.set('No se pudo obtener la ubicación. Elíjala en el mapa.'),
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  }

  protected elegirFoto(evento: Event): void {
    const entrada = evento.target as HTMLInputElement;
    const archivo = entrada.files?.[0] ?? null;
    this.error.set(null);
    if (archivo && !TIPOS_FOTO.includes(archivo.type)) {
      this.error.set('La foto debe ser JPEG o PNG.');
      entrada.value = '';
      this.foto = null;
      return;
    }
    if (archivo && archivo.size > MAXIMO_FOTO) {
      this.error.set('La foto supera 5 MB.');
      entrada.value = '';
      this.foto = null;
      return;
    }
    this.foto = archivo;
  }

  protected enviar(): void {
    const u = this.ubicacion();
    if (!u) {
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    this.api.enviar({
      tipoServicio: this.tipoServicio,
      ubicacion: { latitud: u[0], longitud: u[1] },
      descripcion: this.descripcion.trim(),
    }, this.foto).subscribe({
      next: (r) => {
        this.enviando.set(false);
        this.recibida.set(r);
      },
      error: (e: unknown) => {
        this.enviando.set(false);
        this.error.set(mensajeDeError(e));
      },
    });
  }

  protected nueva(): void {
    this.recibida.set(null);
    this.ubicacion.set(null);
    this.descripcion = '';
    this.foto = null;
  }
}

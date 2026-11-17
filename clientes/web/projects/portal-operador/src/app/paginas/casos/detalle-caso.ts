import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import {
  ApiCasos, COLOR_PRIORIDAD, COLOR_SERVICIO, Caso, FechaCortaPipe, MarcaMapa, Mapa, NOMBRE_ESTADO, NOMBRE_MAGNITUD,
  NOMBRE_PRIORIDAD, NOMBRE_SERVICIO, PRIORIDADES, Prioridad, ServicioSesion, mensajeDeError, motivoDeError,
} from 'compartido';
import { EMPRESAS, nombreEmpresa } from '../../empresas';

/**
 * Detalle de un caso (RF-6, RF-7): datos operativos del reporte (sin ningún
 * dato del ciudadano), historia, evidencias y las acciones según el estado.
 */
@Component({
  selector: 'app-detalle-caso',
  imports: [FormsModule, RouterLink, FechaCortaPipe, Mapa],
  template: `
    <p><a routerLink="/casos">← Casos</a></p>
    @if (error()) { <p class="sr-aviso error" role="alert">{{ error() }}</p> }
    @if (aviso()) { <p class="sr-aviso exito" role="status">{{ aviso() }}</p> }

    @if (caso(); as c) {
      <h1>{{ nombreServicio[c.tipoServicio] }} · {{ c.nombreMunicipio ?? c.codigoMunicipio }}</h1>
      <div class="sr-rejilla">
        <section class="sr-tarjeta">
          <h2>Datos</h2>
          <dl>
            <dt>Estado</dt><dd>{{ nombreEstado[c.estado] }}</dd>
            <dt>Prioridad</dt>
            <dd><span class="sr-chip" [style.color]="colorPrioridad[c.prioridad]">{{ nombrePrioridad[c.prioridad] }}</span>
              @if (c.prioridadManual) { <span class="sr-suave"> (fijada a mano)</span> }</dd>
            <dt>Magnitud</dt><dd>{{ nombreMagnitud[c.magnitud] }}</dd>
            <dt>Validado por la comunidad</dt><dd>{{ c.corteValidado ? 'Sí' : 'No' }}</dd>
            <dt>Inicio estimado</dt><dd>{{ c.inicioEstimado | fechaCorta }}</dd>
            <dt>Abierto</dt><dd>{{ c.abiertoEn | fechaCorta }}</dd>
            <dt>Empresa</dt><dd>{{ empresa(c.idEmpresa) }}</dd>
            <dt>Asignado a</dt><dd>{{ c.asignadoA ? (c.asignadoA === miCuenta() ? 'Usted' : c.asignadoA) : 'Nadie' }}</dd>
            @if (c.resolucion) { <dt>Resolución</dt><dd>{{ c.resolucion }}</dd> }
            @if (c.resueltoEn) { <dt>Resuelto</dt><dd>{{ c.resueltoEn | fechaCorta }}</dd> }
            @if (c.cerradoEn) { <dt>Cerrado</dt><dd>{{ c.cerradoEn | fechaCorta }}</dd> }
          </dl>
          @if (c.descripcion) { <h3>Descripción</h3><p class="descripcion">{{ c.descripcion }}</p> }
        </section>

        @if (marcas().length) {
          <section class="sr-tarjeta">
            <h2>Ubicación</h2>
            <sr-mapa [marcas]="marcas()" [centro]="[c.latitud!, c.longitud!]" [zoom]="15" alto="300px"
                     titulo="Ubicación del reporte" />
          </section>
        }
      </div>

      @if (puedeGestionar() && c.estado !== 'CERRADO') {
        <section class="sr-tarjeta">
          <h2>Acciones</h2>
          <div class="sr-fila">
            @if (c.estado === 'RECIBIDO') {
              <button class="sr-primario" type="button" [disabled]="ocupado()" (click)="ejecutar(api.atender(c.idCaso), 'Caso en atención')">
                Atender
              </button>
            }
            @if (c.idEmpresa && c.asignadoA !== miCuenta()) {
              <button type="button" [disabled]="ocupado()" (click)="ejecutar(api.asignar(c.idCaso), 'Caso asignado a usted')">
                Asignármelo
              </button>
            }
            <label>Prioridad
              <select [(ngModel)]="prioridad" name="prioridad">
                @for (p of prioridades; track p) { <option [ngValue]="p">{{ nombrePrioridad[p] }}</option> }
              </select>
            </label>
            <button type="button" [disabled]="ocupado() || prioridad === c.prioridad"
                    (click)="ejecutar(api.priorizar(c.idCaso, prioridad), 'Prioridad actualizada')">Cambiar prioridad</button>
            @if (esAdmin()) {
              <label>Empresa
                <select [(ngModel)]="empresaDestino" name="empresa">
                  <option [ngValue]="''">Elegir…</option>
                  @for (e of empresas; track e.id) { <option [ngValue]="e.id">{{ e.nombre }}</option> }
                </select>
              </label>
              <button type="button" [disabled]="ocupado() || !empresaDestino || empresaDestino === c.idEmpresa"
                      (click)="ejecutar(api.enrutar(c.idCaso, empresaDestino), 'Caso enrutado')">Enrutar</button>
            }
          </div>

          <label class="texto">{{ c.estado === 'RESUELTO' ? 'Motivo de cierre (opcional)' : 'Resolución o motivo' }}
            <textarea [(ngModel)]="texto" name="texto" maxlength="1000"
                      placeholder="Qué se hizo para restablecer el servicio, o por qué se cierra"></textarea>
          </label>
          <div class="sr-fila">
            @if (c.estado === 'RECIBIDO' || c.estado === 'EN_ATENCION') {
              <button class="sr-primario" type="button" [disabled]="ocupado() || !texto.trim()"
                      (click)="ejecutar(api.resolver(c.idCaso, texto.trim()), 'Caso resuelto')">Resolver</button>
            }
            <button class="sr-peligro" type="button" [disabled]="ocupado() || (c.estado !== 'RESUELTO' && !texto.trim())"
                    (click)="ejecutar(api.cerrar(c.idCaso, texto.trim() || undefined), 'Caso cerrado')">Cerrar caso</button>
          </div>
        </section>
      }

      <div class="sr-rejilla">
        <section class="sr-tarjeta">
          <h2>Historia</h2>
          <ol class="historia">
            @for (h of c.historia ?? []; track $index) {
              <li><strong>{{ nombreEstado[h.estado] }}</strong> · {{ h.fecha | fechaCorta }}<br />{{ h.detalle }}</li>
            }
          </ol>
        </section>
        <section class="sr-tarjeta">
          <h2>Evidencias</h2>
          @for (ev of c.evidencias ?? []; track ev.idEvidencia) {
            @if (fotos()[ev.idEvidencia]; as url) {
              <img [src]="url" alt="Foto adjunta al reporte" class="foto" />
            } @else {
              <button type="button" (click)="verFoto(c.idCaso, ev.idEvidencia)">Ver foto ({{ kb(ev.tamanoBytes) }} KB)</button>
            }
          } @empty {
            <p class="sr-suave">Sin fotos.</p>
          }
        </section>
      </div>
    } @else if (!error()) {
      <p class="sr-suave">Cargando…</p>
    }
  `,
  styles: `
    dl { display: grid; grid-template-columns: max-content 1fr; gap: .3rem 1rem; margin: 0; }
    dt { color: var(--sr-texto-suave); }
    dd { margin: 0; }
    .descripcion { white-space: pre-line; }
    .texto { margin: .75rem 0; }
    .historia { padding-left: 1.2rem; margin: 0; display: flex; flex-direction: column; gap: .5rem; }
    .foto { max-width: 100%; border-radius: 6px; margin-bottom: .5rem; display: block; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetalleCaso {
  protected readonly api = inject(ApiCasos);
  private readonly sesion = inject(ServicioSesion);

  readonly idCaso = input.required<string>();

  protected readonly caso = signal<Caso | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  protected readonly ocupado = signal(false);
  protected readonly fotos = signal<Record<string, string>>({});

  protected prioridad: Prioridad = 'MEDIA';
  protected empresaDestino = '';
  protected texto = '';

  protected readonly prioridades = PRIORIDADES;
  protected readonly empresas = EMPRESAS;
  protected readonly nombreEstado = NOMBRE_ESTADO;
  protected readonly nombreServicio = NOMBRE_SERVICIO;
  protected readonly nombrePrioridad = NOMBRE_PRIORIDAD;
  protected readonly nombreMagnitud = NOMBRE_MAGNITUD;
  protected readonly colorPrioridad = COLOR_PRIORIDAD;
  protected readonly empresa = nombreEmpresa;

  protected readonly esAdmin = computed(() => this.sesion.rol() === 'ADMINISTRADOR');
  /** El regulador solo consulta (el gateway le rechaza cualquier cambio). */
  protected readonly puedeGestionar = computed(() => this.sesion.tieneRol('OPERADOR', 'ADMINISTRADOR'));
  protected readonly miCuenta = computed(() => this.sesion.cuenta()?.idCuenta);
  protected readonly marcas = computed<MarcaMapa[]>(() => {
    const c = this.caso();
    return c?.latitud != null && c.longitud != null
      ? [{ latitud: c.latitud, longitud: c.longitud, color: COLOR_SERVICIO[c.tipoServicio], radio: 10 }]
      : [];
  });

  constructor() {
    effect(() => this.cargar(this.idCaso()));
    inject(DestroyRef).onDestroy(() => Object.values(this.fotos()).forEach((u) => URL.revokeObjectURL(u)));
  }

  protected ejecutar(accion: Observable<Caso>, exito: string): void {
    this.ocupado.set(true);
    this.error.set(null);
    this.aviso.set(null);
    accion.subscribe({
      next: (c) => {
        this.ocupado.set(false);
        this.aviso.set(exito);
        this.texto = '';
        // La respuesta no siempre trae historia/evidencias: se recarga el detalle completo.
        this.cargar(c.idCaso);
      },
      error: (e: unknown) => {
        this.ocupado.set(false);
        // 409 por versión: otro operador cambió el caso; se recarga para no pisarlo.
        if (motivoDeError(e) === 'VERSION') {
          this.cargar(this.idCaso());
        }
        this.error.set(mensajeDeError(e));
      },
    });
  }

  protected verFoto(idCaso: string, idEvidencia: string): void {
    this.api.evidencia(idCaso, idEvidencia).subscribe({
      next: (blob) => this.fotos.update((f) => ({ ...f, [idEvidencia]: URL.createObjectURL(blob) })),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }

  protected kb(bytes: number): number {
    return Math.max(1, Math.round(bytes / 1024));
  }

  private cargar(idCaso: string): void {
    this.api.obtener(idCaso).subscribe({
      next: (c) => {
        this.caso.set(c);
        this.prioridad = c.prioridad;
      },
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }
}

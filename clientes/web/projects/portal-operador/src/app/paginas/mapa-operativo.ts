import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import {
  ApiIndicadores, COLOR_PRIORIDAD, COLOR_SERVICIO, CORTO_SERVICIO, EMOJI_SERVICIO, MarcaMapa, Mapa, NOMBRE_ESTADO,
  NOMBRE_MAGNITUD, NOMBRE_PRIORIDAD, PuntoOperativo, SelectorTerritorio, TIPOS_SERVICIO, TipoServicio, fechaCorta, filtroDePeriodo,
  formularioPeriodo, mensajeDeError,
} from 'compartido';
import { Empresas } from '../empresas';

const PERIODOS = [
  { dias: 1, texto: '24 h' },
  { dias: 7, texto: '7 días' },
  { dias: 30, texto: '30 días' },
] as const;

type Situacion = 'ACTIVO' | 'RESUELTO' | undefined;

/**
 * Mapa operativo (RF-9) a pantalla completa, como en las apps: puntos exactos de
 * los reportes, solo para el personal, con el ícono de su servicio; los de
 * prioridad alta o crítica se ven más grandes. Al tocar uno aparece su tarjeta.
 */
@Component({
  selector: 'app-mapa-operativo',
  imports: [Mapa, SelectorTerritorio],
  template: `
    <div class="sr-pantalla-mapa">
      <sr-mapa [marcas]="marcas()" alto="100%" titulo="Mapa operativo de reportes" posicionZoom="bottomright"
               [ventanas]="false" (marcaElegida)="elegido.set($event)" (fondoElegido)="elegido.set(null)" />
    </div>
    <h1 class="sr-oculto">Mapa operativo</h1>

    <div class="sr-chips flotantes" role="group" aria-label="Servicio">
      <button type="button" class="sr-chip-filtro" [attr.aria-pressed]="!servicio()" (click)="filtrar(undefined)">Todos</button>
      @for (t of tipos; track t) {
        <button type="button" class="sr-chip-filtro" [attr.aria-pressed]="servicio() === t" (click)="filtrar(t)">
          <span aria-hidden="true">{{ emoji[t] }}</span> {{ corto[t] }}
        </button>
      }
    </div>

    <div class="sr-inferior">
      @if (error()) { <p class="sr-aviso error sr-hoja" role="alert">{{ error() }}</p> }
      @if (seleccionado(); as p) {
        <section class="sr-hoja borde" [style.border-color]="colores[p.tipoServicio]" aria-live="polite">
          <div class="sr-encabezado-tarjeta">
            <span class="emoji" aria-hidden="true">{{ emoji[p.tipoServicio] }}</span>
            <div class="titulos">
              <strong>{{ corto[p.tipoServicio] }}{{ p.magnitud ? ' · ' + magnitud[p.magnitud] : '' }}</strong>
              <span class="sr-suave">Reportado el {{ fecha(p.fechaRegistro) }}</span>
            </div>
            <button type="button" class="sr-cerrar" aria-label="Cerrar" (click)="elegido.set(null)">✕</button>
          </div>
          <div class="etiquetas">
            <span class="sr-chip">{{ p.estadoCaso ? estado[p.estadoCaso] : 'Sin caso' }}</span>
            @if (p.prioridad) {
              <span class="sr-chip" [style.color]="colorPrioridad[p.prioridad]">Prioridad {{ prioridad[p.prioridad].toLowerCase() }}</span>
            }
            @if (p.validado) { <span class="sr-chip validado">✓ Validado por la comunidad</span> }
          </div>
          @if (p.idEmpresa) { <p class="sr-suave empresa">{{ empresa(p.idEmpresa) }}</p> }
        </section>
      }
      <section class="sr-hoja" aria-label="Resumen y filtros">
        <div class="cabecera">
          <strong aria-live="polite">{{ puntos().length }} reportes</strong>
          <div class="sr-chips" role="group" aria-label="Periodo">
            @for (p of periodos; track p.dias) {
              <button type="button" class="sr-chip-filtro" [attr.aria-pressed]="dias() === p.dias" (click)="periodo(p.dias)">
                {{ p.texto }}
              </button>
            }
          </div>
        </div>
        <div class="sr-chips" role="group" aria-label="Situación">
          <button type="button" class="sr-chip-filtro" [attr.aria-pressed]="!situacion()" (click)="cambiarSituacion(undefined)">Todos</button>
          <button type="button" class="sr-chip-filtro" [attr.aria-pressed]="situacion() === 'ACTIVO'" (click)="cambiarSituacion('ACTIVO')">Activos</button>
          <button type="button" class="sr-chip-filtro" [attr.aria-pressed]="situacion() === 'RESUELTO'" (click)="cambiarSituacion('RESUELTO')">Resueltos</button>
        </div>
        <div class="territorio" role="group" aria-label="Territorio">
          <sr-selector-territorio [f]="territorio" (cambio)="cargar()" />
        </div>
      </section>
    </div>
  `,
  styles: `
    .cabecera { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .5rem; margin-bottom: .5rem; }
    .sr-hoja .sr-chip-filtro { min-height: 2rem; padding: 0 .7rem; font-size: .85rem; box-shadow: none; }
    .territorio { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem; margin-top: .6rem; }
    .etiquetas { display: flex; flex-wrap: wrap; gap: .4rem; margin-top: .7rem; }
    .validado { color: var(--sr-exito); }
    .empresa { margin: .5rem 0 0; font-size: .88rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MapaOperativo {
  private readonly api = inject(ApiIndicadores);

  protected readonly tipos = TIPOS_SERVICIO;
  protected readonly emoji = EMOJI_SERVICIO;
  protected readonly corto = CORTO_SERVICIO;
  protected readonly colores = COLOR_SERVICIO;
  protected readonly colorPrioridad = COLOR_PRIORIDAD;
  protected readonly estado = NOMBRE_ESTADO;
  protected readonly prioridad = NOMBRE_PRIORIDAD;
  protected readonly magnitud = NOMBRE_MAGNITUD;
  protected readonly periodos = PERIODOS;
  protected readonly fecha = fechaCorta;

  protected readonly servicio = signal<TipoServicio | undefined>(undefined);
  protected readonly situacion = signal<Situacion>(undefined);
  protected readonly dias = signal(7);
  /** Departamento y municipio elegidos en las listas ('' = todos). */
  protected territorio = { codigoDepartamento: '', codigoMunicipio: '' };
  protected readonly puntos = signal<PuntoOperativo[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly elegido = signal<string | null>(null);

  protected readonly seleccionado = computed(() => this.puntos().find((p) => p.idReporte === this.elegido()) ?? null);

  protected readonly marcas = computed<MarcaMapa[]>(() => this.puntos().map((p) => ({
    id: p.idReporte,
    latitud: p.latitud,
    longitud: p.longitud,
    color: COLOR_SERVICIO[p.tipoServicio],
    radio: p.prioridad === 'CRITICA' ? 22 : p.prioridad === 'ALTA' ? 19 : 16,
    icono: EMOJI_SERVICIO[p.tipoServicio],
    etiqueta: [
      CORTO_SERVICIO[p.tipoServicio],
      p.estadoCaso ? NOMBRE_ESTADO[p.estadoCaso] : 'Sin caso',
      p.prioridad ? `Prioridad ${NOMBRE_PRIORIDAD[p.prioridad].toLowerCase()}` : '',
    ].filter(Boolean).join(' · '),
    resaltada: p.idReporte === this.elegido(),
  })));

  constructor() {
    this.cargar();
    const reloj = setInterval(() => this.cargar(), 60_000);
    inject(DestroyRef).onDestroy(() => clearInterval(reloj));
  }

  private readonly empresas = inject(Empresas);
  protected empresa(id?: string | null): string {
    return this.empresas.nombre(id);
  }

  protected filtrar(t: TipoServicio | undefined): void {
    this.servicio.set(t);
    this.cargar();
  }

  protected periodo(dias: number): void {
    this.dias.set(dias);
    this.cargar();
  }

  protected cambiarSituacion(s: Situacion): void {
    this.situacion.set(s);
    this.cargar();
  }

  protected cargar(): void {
    const f = formularioPeriodo(this.dias());
    f.tipoServicio = this.servicio();
    f.situacion = this.situacion();
    f.codigoDepartamento = this.territorio.codigoDepartamento;
    f.codigoMunicipio = this.territorio.codigoMunicipio;
    this.api.mapaOperativo(filtroDePeriodo(f)).subscribe({
      next: (p) => {
        this.puntos.set(p);
        this.error.set(null);
      },
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }
}

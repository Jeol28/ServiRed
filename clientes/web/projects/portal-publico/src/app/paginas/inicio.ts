import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  ApiIndicadores, COLOR_SERVICIO, CORTO_SERVICIO, DuracionPipe, EMOJI_SERVICIO, MapaPublico, MarcaMapa, Mapa,
  ResumenIndicadores, TIPOS_SERVICIO, TipoServicio, filtroDePeriodo, formularioPeriodo, mensajeDeError,
} from 'compartido';
import { MetadatosPublicacion } from './metadatos';

function clave(c: { latitud: number; longitud: number }): string {
  return `${c.latitud},${c.longitud}`;
}

/** Color de las zonas cuando se ven todos los servicios. */
const COLOR_TODOS = '#c2410c';

const PERIODOS = [
  { dias: 1, texto: '24 h' },
  { dias: 7, texto: '7 días' },
  { dias: 30, texto: '30 días' },
] as const;

/**
 * Mapa público (RF-9) a pantalla completa, como en las apps: zonas de ~1 km con
 * el número de reportes. Las zonas con menos de 3 reportes no se publican
 * (k-anonimato, RNF-DAT2). Se filtra por servicio y periodo con chips y se
 * refresca cada minuto.
 */
@Component({
  selector: 'app-inicio',
  imports: [DecimalPipe, DuracionPipe, Mapa, MetadatosPublicacion, RouterLink],
  template: `
    <div class="sr-pantalla-mapa">
      <sr-mapa [marcas]="marcas()" alto="100%" titulo="Mapa de zonas con interrupciones reportadas"
               posicionZoom="bottomright" [ventanas]="false"
               (marcaElegida)="elegir($event)" (fondoElegido)="zona.set(null)" />
    </div>
    <h1 class="sr-oculto">Interrupciones de servicios públicos</h1>

    <div class="sr-chips flotantes" role="group" aria-label="Servicio">
      <button type="button" class="sr-chip-filtro" [attr.aria-pressed]="!servicio()" (click)="filtrar(undefined)">Todos</button>
      @for (t of tipos; track t) {
        <button type="button" class="sr-chip-filtro" [attr.aria-pressed]="servicio() === t" (click)="filtrar(t)">
          <span aria-hidden="true">{{ emoji[t] }}</span> {{ corto[t] }}
        </button>
      }
    </div>

    <div class="sr-inferior">
      @if (error()) {
        <p class="sr-aviso error sr-hoja" role="alert">{{ error() }}</p>
      }
      @if (zonaElegida(); as z) {
        <section class="sr-hoja borde" [style.border-color]="color()" aria-live="polite">
          <div class="sr-encabezado-tarjeta">
            <span class="emoji" aria-hidden="true">{{ servicio() ? emoji[servicio()!] : '⚠️' }}</span>
            <div class="titulos">
              <strong>{{ z.reportes }} reportes en esta zona</strong>
              <span class="sr-suave">
                {{ servicio() ? corto[servicio()!] + ' · ' : '' }}zona de ~1 km · últimos {{ periodoTexto() }}
              </span>
            </div>
            <button type="button" class="sr-cerrar" aria-label="Cerrar" (click)="zona.set(null)">✕</button>
          </div>
        </section>
      }
      <section class="sr-hoja resumen" aria-labelledby="titulo-resumen">
        <div class="cabecera">
          <h2 id="titulo-resumen">{{ servicio() ? 'Cortes de ' + corto[servicio()!].toLowerCase() : 'Cortes reportados' }}</h2>
          <div class="sr-chips" role="group" aria-label="Periodo">
            @for (p of periodos; track p.dias) {
              <button type="button" class="sr-chip-filtro" [attr.aria-pressed]="dias() === p.dias" (click)="periodo(p.dias)">
                {{ p.texto }}
              </button>
            }
          </div>
        </div>
        @if (resumen(); as r) {
          @if (r.indicadores.suprimido) {
            <p class="sr-suave">Muy pocos reportes en este periodo para publicarlos (menos de 3), para proteger la privacidad.</p>
          } @else {
            <dl class="cifras">
              <div><dt>Reportes</dt><dd>{{ r.indicadores.reportes | number }}</dd></div>
              <div><dt>Duración mediana</dt><dd>{{ r.indicadores.duracion?.mediana | duracion }}</dd></div>
              <div>
                <dt>Resueltos</dt>
                <dd>{{ r.indicadores.atencion?.porcentajeResueltos != null ? (r.indicadores.atencion!.porcentajeResueltos | number: '1.0-0') + ' %' : '—' }}</dd>
              </div>
            </dl>
          }
        } @else {
          <p class="sr-suave">Cargando…</p>
        }
        <p class="sr-suave zonas" aria-live="polite">
          {{ mapa()?.celdas?.length ?? 0 }} zonas con reportes. Toque una para ver cuántos hay.
        </p>
        <div class="acciones">
          <a routerLink="/indicadores">Ver indicadores →</a>
          <button type="button" class="enlace" [attr.aria-expanded]="acerca()" (click)="acerca.set(!acerca())">
            Acerca de estos datos
          </button>
        </div>
        @if (acerca() && mapa(); as m) { <app-metadatos [m]="m.metadatos" /> }
        <p class="nota">📱 Para reportar un corte, use la app SERVI-RED en su teléfono.</p>
      </section>
    </div>
  `,
  styles: `
    .cabecera { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .5rem; margin-bottom: .6rem; }
    h2 { margin: 0; font-size: 1.1rem; }
    .cabecera .sr-chip-filtro { min-height: 2rem; padding: 0 .7rem; font-size: .85rem; box-shadow: none; }
    .cifras { display: grid; grid-template-columns: repeat(3, 1fr); gap: .5rem; margin: 0; }
    .cifras dt { font-size: .78rem; color: var(--sr-texto-suave); }
    .cifras dd { margin: 0; font-size: 1.25rem; font-weight: 700; }
    .zonas { font-size: .85rem; margin: .6rem 0 .2rem; }
    .acciones { display: flex; justify-content: space-between; align-items: center; gap: .5rem; font-size: .9rem; }
    .enlace { border: 0; background: none; color: var(--sr-primario); padding: 0; min-height: 0; text-decoration: underline; }
    .nota { margin: .7rem 0 0; font-size: .85rem; color: var(--sr-texto-suave); }
    app-metadatos { display: block; margin-top: .6rem; font-size: .85rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Inicio {
  private readonly api = inject(ApiIndicadores);
  protected readonly tipos = TIPOS_SERVICIO;
  protected readonly emoji = EMOJI_SERVICIO;
  protected readonly corto = CORTO_SERVICIO;
  protected readonly periodos = PERIODOS;

  protected readonly servicio = signal<TipoServicio | undefined>(undefined);
  protected readonly dias = signal(30);
  protected readonly mapa = signal<MapaPublico | null>(null);
  protected readonly resumen = signal<ResumenIndicadores | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Zona elegida, por sus coordenadas: sigue siendo la misma al refrescar. */
  protected readonly zona = signal<string | null>(null);
  protected readonly acerca = signal(false);

  protected readonly color = computed(() => {
    const s = this.servicio();
    return s ? COLOR_SERVICIO[s] : COLOR_TODOS;
  });
  protected readonly periodoTexto = computed(() => PERIODOS.find((p) => p.dias === this.dias())?.texto ?? '');
  protected readonly zonaElegida = computed(() => {
    const id = this.zona();
    return id === null ? null : this.mapa()?.celdas.find((c) => clave(c) === id) ?? null;
  });

  protected readonly marcas = computed<MarcaMapa[]>(() => {
    const celdas = this.mapa()?.celdas ?? [];
    const maximo = Math.max(1, ...celdas.map((c) => c.reportes));
    const color = this.color();
    return celdas.map((c) => ({
      id: clave(c),
      latitud: c.latitud,
      longitud: c.longitud,
      color,
      radio: 14 + 10 * Math.sqrt(c.reportes / maximo),
      icono: String(c.reportes),
      etiqueta: `${c.reportes} reportes en esta zona`,
      resaltada: clave(c) === this.zona(),
    }));
  });

  constructor() {
    this.cargar();
    // "Tiempo real": se refresca cada minuto mientras el mapa está abierto.
    const reloj = setInterval(() => this.cargar(), 60_000);
    inject(DestroyRef).onDestroy(() => clearInterval(reloj));
  }

  protected filtrar(t: TipoServicio | undefined): void {
    this.servicio.set(t);
    this.zona.set(null);
    this.cargar();
  }

  protected periodo(dias: number): void {
    this.dias.set(dias);
    this.zona.set(null);
    this.cargar();
  }

  protected elegir(id: string): void {
    this.zona.set(id);
  }

  protected cargar(): void {
    const f = formularioPeriodo(this.dias());
    f.tipoServicio = this.servicio();
    const filtro = filtroDePeriodo(f);
    this.api.mapaPublico(filtro).subscribe({
      next: (m) => {
        this.mapa.set(m);
        this.error.set(null);
      },
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
    this.api.resumen(filtro).subscribe({
      next: (r) => this.resumen.set(r),
      error: (e: unknown) => this.error.set(mensajeDeError(e)),
    });
  }
}

import {
  AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, OnDestroy, effect, input, output, viewChild,
} from '@angular/core';
import * as L from 'leaflet';

/** Un elemento dibujable: un círculo (punto o celda) con color, radio y texto emergente. */
export interface MarcaMapa {
  latitud: number;
  longitud: number;
  color: string;
  /** Radio en píxeles. */
  radio?: number;
  /** Texto plano: se inserta como texto, nunca como HTML. */
  etiqueta?: string;
  id?: string;
  /**
   * Si se da, la marca es un ícono redondo (como en las apps) con este texto
   * corto dentro: un emoji o una cifra. Se inserta con `textContent`.
   */
  icono?: string;
  /** Resalta la marca (anillo oscuro), p. ej. la seleccionada. */
  resaltada?: boolean;
}

/** Centro geográfico aproximado de Colombia. */
const COLOMBIA: [number, number] = [4.6, -74.1];

/**
 * Mapa base de OpenStreetMap con Leaflet. Dibuja marcas circulares o íconos
 * redondos con un emoji (sin imágenes de marcador, que exigen recursos externos)
 * y opcionalmente permite elegir una ubicación con un clic.
 */
@Component({
  selector: 'sr-mapa',
  template: `<div #contenedor class="sr-mapa" [style.height]="alto()" role="region" [attr.aria-label]="titulo()"></div>`,
  styles: `:host { display: block; } .sr-mapa { width: 100%; border-radius: var(--sr-radio, 8px); z-index: 0; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Mapa implements AfterViewInit, OnDestroy {
  readonly marcas = input<MarcaMapa[]>([]);
  readonly alto = input('420px');
  readonly titulo = input('Mapa');
  readonly zoom = input(6);
  readonly centro = input<[number, number]>(COLOMBIA);
  /** Si es true, un clic emite la ubicación y deja un marcador de selección. */
  readonly seleccionable = input(false);
  readonly seleccion = input<[number, number] | null>(null);
  /** Esquina de los botones de zoom: abajo a la derecha cuando el mapa ocupa la pantalla. */
  readonly posicionZoom = input<L.ControlPosition>('topleft');
  /** Si es false, tocar una marca solo emite `marcaElegida` (la página muestra su tarjeta). */
  readonly ventanas = input(true);

  readonly ubicacionElegida = output<{ latitud: number; longitud: number }>();
  readonly marcaElegida = output<string>();
  /** Clic en el mapa fuera de las marcas (p. ej. para cerrar una tarjeta). */
  readonly fondoElegido = output<void>();

  private readonly contenedor = viewChild.required<ElementRef<HTMLDivElement>>('contenedor');
  private mapa?: L.Map;
  private capa = L.layerGroup();
  private marcadorSeleccion?: L.CircleMarker;

  constructor() {
    effect(() => this.dibujar(this.marcas()));
    effect(() => this.marcarSeleccion(this.seleccion()));
  }

  ngAfterViewInit(): void {
    this.mapa = L.map(this.contenedor().nativeElement, { center: this.centro(), zoom: this.zoom(), zoomControl: false });
    L.control.zoom({ position: this.posicionZoom(), zoomInTitle: 'Acercar', zoomOutTitle: 'Alejar' }).addTo(this.mapa);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; colaboradores de <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(this.mapa);
    this.capa.addTo(this.mapa);
    this.mapa.on('click', (e: L.LeafletMouseEvent) => {
      this.fondoElegido.emit();
      if (this.seleccionable()) {
        const latitud = redondear(e.latlng.lat);
        const longitud = redondear(e.latlng.lng);
        this.marcarSeleccion([latitud, longitud]);
        this.ubicacionElegida.emit({ latitud, longitud });
      }
    });
    this.dibujar(this.marcas());
    this.marcarSeleccion(this.seleccion());
  }

  ngOnDestroy(): void {
    this.mapa?.remove();
  }

  /** Centra el mapa en un punto (p. ej. tras obtener la ubicación del navegador). */
  irA(latitud: number, longitud: number, zoom = 15): void {
    this.mapa?.setView([latitud, longitud], zoom);
  }

  private dibujar(marcas: MarcaMapa[]): void {
    if (!this.mapa) {
      return;
    }
    this.capa.clearLayers();
    for (const m of marcas) {
      const c = m.icono !== undefined ? icono(m) : L.circleMarker([m.latitud, m.longitud], {
        radius: m.radio ?? 7, color: m.color, fillColor: m.color, fillOpacity: 0.55, weight: 1.5,
      });
      if (m.etiqueta && this.ventanas()) {
        const texto = document.createElement('span');
        texto.style.whiteSpace = 'pre-line';
        texto.textContent = m.etiqueta;
        c.bindPopup(texto);
      }
      if (m.id) {
        const id = m.id;
        c.on('click', (e: L.LeafletMouseEvent) => {
          L.DomEvent.stopPropagation(e);
          this.marcaElegida.emit(id);
        });
        // Con teclado: Enter sobre un ícono enfocado.
        c.on('keypress', (e: L.LeafletKeyboardEvent) => {
          if (e.originalEvent.key === 'Enter') { this.marcaElegida.emit(id); }
        });
      }
      c.addTo(this.capa);
    }
  }

  private marcarSeleccion(punto: [number, number] | null): void {
    if (!this.mapa) {
      return;
    }
    this.marcadorSeleccion?.remove();
    this.marcadorSeleccion = undefined;
    if (punto) {
      this.marcadorSeleccion = L.circleMarker(punto, {
        radius: 9, color: '#0b5394', fillColor: '#0b5394', fillOpacity: 0.9, weight: 3,
      }).addTo(this.mapa);
    }
  }
}

/**
 * Ícono redondo del color dado con un emoji o una cifra dentro. Es enfocable con
 * el teclado y lleva la etiqueta como título accesible.
 */
function icono(m: MarcaMapa): L.Marker {
  const lado = Math.round((m.radio ?? 16) * 2);
  const div = document.createElement('div');
  div.className = 'sr-icono-mapa' + (m.resaltada ? ' resaltada' : '');
  div.style.background = m.color;
  div.style.width = div.style.height = `${lado}px`;
  div.style.fontSize = `${Math.max(11, Math.round(lado * 0.45))}px`;
  div.textContent = m.icono ?? '';
  return L.marker([m.latitud, m.longitud], {
    icon: L.divIcon({ html: div, className: 'sr-icono-mapa-contenedor', iconSize: [lado, lado] }),
    title: m.etiqueta?.split('\n')[0] ?? '',
    alt: m.etiqueta ?? '',
    keyboard: m.id !== undefined,
    riseOnHover: true,
    zIndexOffset: m.resaltada ? 1000 : 0,
  });
}

/** 5 decimales (~1 m) bastan para un reporte. */
function redondear(v: number): number {
  return Math.round(v * 1e5) / 1e5;
}

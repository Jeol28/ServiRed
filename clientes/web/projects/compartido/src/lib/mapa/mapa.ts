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
}

/** Centro geográfico aproximado de Colombia. */
const COLOMBIA: [number, number] = [4.6, -74.1];

/**
 * Mapa base de OpenStreetMap con Leaflet. Dibuja marcas circulares (sin
 * imágenes de marcador, que exigen recursos externos) y opcionalmente permite
 * elegir una ubicación con un clic.
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

  readonly ubicacionElegida = output<{ latitud: number; longitud: number }>();
  readonly marcaElegida = output<string>();

  private readonly contenedor = viewChild.required<ElementRef<HTMLDivElement>>('contenedor');
  private mapa?: L.Map;
  private capa = L.layerGroup();
  private marcadorSeleccion?: L.CircleMarker;

  constructor() {
    effect(() => this.dibujar(this.marcas()));
    effect(() => this.marcarSeleccion(this.seleccion()));
  }

  ngAfterViewInit(): void {
    this.mapa = L.map(this.contenedor().nativeElement, { center: this.centro(), zoom: this.zoom() });
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; colaboradores de <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(this.mapa);
    this.capa.addTo(this.mapa);
    this.mapa.on('click', (e: L.LeafletMouseEvent) => {
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
      const c = L.circleMarker([m.latitud, m.longitud], {
        radius: m.radio ?? 7, color: m.color, fillColor: m.color, fillOpacity: 0.55, weight: 1.5,
      });
      if (m.etiqueta) {
        const texto = document.createElement('span');
        texto.style.whiteSpace = 'pre-line';
        texto.textContent = m.etiqueta;
        c.bindPopup(texto);
      }
      if (m.id) {
        const id = m.id;
        c.on('click', () => this.marcaElegida.emit(id));
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

/** 5 decimales (~1 m) bastan para un reporte. */
function redondear(v: number): number {
  return Math.round(v * 1e5) / 1e5;
}

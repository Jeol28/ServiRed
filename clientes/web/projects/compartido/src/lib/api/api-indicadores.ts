import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../configuracion';
import {
  CatalogoDatosAbiertos, DefinicionIndicador, FiltroIndicadores, IndicadoresTerritoriales, MapaPublico,
  PuntoOperativo, ResumenIndicadores,
} from '../modelos';
import { parametros } from './parametros';

export interface CatalogoIndicadores {
  indicadores: DefinicionIndicador[];
  fuente: string;
  umbralSupresion: number;
  celdaMapaGrados: number;
}

/**
 * Indicadores, mapas y datos abiertos (RF-8 a RF-10). Lo público es agregado y
 * anonimizado (RNF-DAT2); el mapa operativo y la exportación regulatoria exigen rol.
 */
@Injectable({ providedIn: 'root' })
export class ApiIndicadores {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_BASE)}/api/v1`;

  catalogo(): Observable<CatalogoIndicadores> {
    return this.http.get<CatalogoIndicadores>(`${this.base}/indicadores/catalogo`);
  }

  resumen(filtro: FiltroIndicadores = {}): Observable<ResumenIndicadores> {
    return this.http.get<ResumenIndicadores>(`${this.base}/indicadores/resumen`, { params: parametros(filtro) });
  }

  territorial(nivel: 'DEPARTAMENTO' | 'MUNICIPIO', filtro: FiltroIndicadores = {}): Observable<IndicadoresTerritoriales> {
    return this.http.get<IndicadoresTerritoriales>(`${this.base}/indicadores/territorial`,
      { params: parametros({ ...filtro, nivel }) });
  }

  mapaPublico(filtro: FiltroIndicadores = {}): Observable<MapaPublico> {
    return this.http.get<MapaPublico>(`${this.base}/mapas/publico`, { params: parametros(filtro) });
  }

  mapaOperativo(filtro: FiltroIndicadores & { idEmpresa?: string } = {}): Observable<PuntoOperativo[]> {
    return this.http.get<PuntoOperativo[]>(`${this.base}/mapas/operativo`, { params: parametros(filtro) });
  }

  /** Exportación regulatoria (regulador y administrador); queda en la auditoría. */
  exportarReportes(filtro: FiltroIndicadores, formato: 'csv' | 'json'): Observable<Blob> {
    return this.http.get(`${this.base}/indicadores/exportaciones/reportes`,
      { params: parametros({ ...filtro, formato }), responseType: 'blob' });
  }

  catalogoDatosAbiertos(): Observable<CatalogoDatosAbiertos> {
    return this.http.get<CatalogoDatosAbiertos>(`${this.base}/datos-abiertos/catalogo`);
  }

  datosAbiertosMunicipales(filtro: FiltroIndicadores, formato: 'csv' | 'json'): Observable<Blob> {
    return this.http.get(`${this.base}/datos-abiertos/indicadores-municipales`,
      { params: parametros({ ...filtro, formato }), responseType: 'blob' });
  }
}

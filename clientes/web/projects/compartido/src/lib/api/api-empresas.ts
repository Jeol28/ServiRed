import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { API_BASE } from '../configuracion';
import { EmpresaPrestadora, FiltroEmpresas } from '../modelos';
import { parametros } from './parametros';

/**
 * Catálogo de empresas prestadoras reales (ADR-35), para el personal. Son unas 1800 y casi no
 * cambian: la lista completa se pide una vez por sesión del navegador; las filtradas por
 * servicio o territorio se piden cada vez.
 */
@Injectable({ providedIn: 'root' })
export class ApiEmpresas {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE)}/api/v1/empresas`;
  private cache?: Observable<EmpresaPrestadora[]>;

  listar(filtro: FiltroEmpresas = {}): Observable<EmpresaPrestadora[]> {
    const params = parametros(filtro);
    if (params.keys().length > 0) {
      return this.http.get<EmpresaPrestadora[]>(this.url, { params });
    }
    this.cache ??= this.http.get<EmpresaPrestadora[]>(this.url).pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.cache;
  }
}

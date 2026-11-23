import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { API_BASE } from '../configuracion';
import { EmpresaPrestadora } from '../modelos';

/**
 * Catálogo de empresas prestadoras reales (ADR-35), para el personal. Son unas 1800 y casi no
 * cambian: se piden una vez por sesión del navegador.
 */
@Injectable({ providedIn: 'root' })
export class ApiEmpresas {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE)}/api/v1/empresas`;
  private cache?: Observable<EmpresaPrestadora[]>;

  listar(): Observable<EmpresaPrestadora[]> {
    this.cache ??= this.http.get<EmpresaPrestadora[]>(this.url).pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.cache;
  }
}

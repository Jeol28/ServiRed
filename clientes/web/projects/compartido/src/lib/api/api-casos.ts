import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../configuracion';
import { Caso, FiltroCasos, Pagina, Prioridad, ZonaCaso } from '../modelos';
import { parametros } from './parametros';

/** Atención de casos (RF-6, RF-7). Un operador solo ve los casos de su empresa. */
@Injectable({ providedIn: 'root' })
export class ApiCasos {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE)}/api/v1/casos`;

  listar(filtro: FiltroCasos = {}): Observable<Pagina<Caso>> {
    return this.http.get<Pagina<Caso>>(this.url, { params: parametros(filtro) });
  }

  obtener(idCaso: string): Observable<Caso> {
    return this.http.get<Caso>(`${this.url}/${idCaso}`);
  }

  atender(idCaso: string): Observable<Caso> {
    return this.http.post<Caso>(`${this.url}/${idCaso}/atender`, {});
  }

  resolver(idCaso: string, resolucion: string): Observable<Caso> {
    return this.http.post<Caso>(`${this.url}/${idCaso}/resolver`, { resolucion });
  }

  cerrar(idCaso: string, resolucion?: string): Observable<Caso> {
    return this.http.post<Caso>(`${this.url}/${idCaso}/cerrar`, resolucion ? { resolucion } : {});
  }

  /** Sin `idOperador` el caso se asigna a quien lo pide. */
  asignar(idCaso: string, idOperador?: string): Observable<Caso> {
    return this.http.put<Caso>(`${this.url}/${idCaso}/asignacion`, idOperador ? { idOperador } : {});
  }

  priorizar(idCaso: string, prioridad: Prioridad): Observable<Caso> {
    return this.http.put<Caso>(`${this.url}/${idCaso}/prioridad`, { prioridad });
  }

  /** Zona del caso y su única empresa dueña (ADR-35). */
  zona(idCaso: string): Observable<ZonaCaso> {
    return this.http.get<ZonaCaso>(`${this.url}/${idCaso}/zona`);
  }

  /** Solo el administrador enruta un caso, y solo a la empresa dueña de su zona (ADR-35). */
  enrutar(idCaso: string, idEmpresa: string): Observable<Caso> {
    return this.http.put<Caso>(`${this.url}/${idCaso}/empresa`, { idEmpresa });
  }

  /** La foto llega por el gateway con el JWT; se devuelve como blob para mostrarla con un object URL. */
  evidencia(idCaso: string, idEvidencia: string): Observable<Blob> {
    return this.http.get(`${this.url}/${idCaso}/evidencias/${idEvidencia}`, { responseType: 'blob' });
  }
}

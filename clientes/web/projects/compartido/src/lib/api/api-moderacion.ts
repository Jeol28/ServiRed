import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../configuracion';
import { EstadoModeracion, Moderacion, Pagina } from '../modelos';
import { parametros } from './parametros';

/** Moderación de denuncias anónimas (RF-5). Nunca hay datos del denunciante. */
@Injectable({ providedIn: 'root' })
export class ApiModeracion {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE)}/api/v1/moderacion/denuncias`;

  listar(filtro: { estado?: EstadoModeracion; pagina?: number; tamano?: number } = {}): Observable<Pagina<Moderacion>> {
    return this.http.get<Pagina<Moderacion>>(this.url, { params: parametros(filtro) });
  }

  obtener(idDenuncia: string): Observable<Moderacion> {
    return this.http.get<Moderacion>(`${this.url}/${idDenuncia}`);
  }

  decidir(idDenuncia: string, decision: 'VALIDADA' | 'DESCARTADA', nota: string): Observable<Moderacion> {
    return this.http.post<Moderacion>(`${this.url}/${idDenuncia}/decision`, { decision, nota });
  }

  evidencia(idDenuncia: string, idEvidencia: string): Observable<Blob> {
    return this.http.get(`${this.url}/${idDenuncia}/evidencias/${idEvidencia}`, { responseType: 'blob' });
  }
}

import { HttpBackend, HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../configuracion';
import { DenunciaRecibida, NuevaDenuncia } from '../modelos';

/**
 * Denuncia anónima (RF-5, RNF-SEG4). Usa un cliente SIN interceptores: no se
 * envía JWT, correlationId ni ningún otro identificador, aunque haya una sesión
 * abierta en la pestaña. El gateway además retira Authorization y Cookie.
 */
@Injectable({ providedIn: 'root' })
export class ApiDenuncias {
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly url = `${inject(API_BASE)}/api/v1/denuncias`;

  enviar(denuncia: NuevaDenuncia, foto?: File | null): Observable<DenunciaRecibida> {
    if (!foto) {
      return this.http.post<DenunciaRecibida>(this.url, denuncia, { withCredentials: false });
    }
    const formulario = new FormData();
    formulario.append('denuncia', new Blob([JSON.stringify(denuncia)], { type: 'application/json' }));
    // Nombre neutro: el nombre original del archivo podría identificar al denunciante.
    formulario.append('foto', foto, 'foto');
    return this.http.post<DenunciaRecibida>(this.url, formulario, { withCredentials: false });
  }
}

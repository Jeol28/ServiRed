import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../configuracion';
import { CategoriaAuditoria, Pagina, RegistroAuditoria, VerificacionAuditoria } from '../modelos';
import { parametros } from './parametros';

export interface FiltroAuditoria {
  categoria?: CategoriaAuditoria;
  accion?: string;
  actorId?: string;
  recursoId?: string;
  desde?: string;
  hasta?: string;
  pagina?: number;
  tamano?: number;
}

/** Registro de auditoría y alertas (RF-12, RF-13): regulador y administrador. */
@Injectable({ providedIn: 'root' })
export class ApiAuditoria {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE)}/api/v1/auditoria`;

  registros(filtro: FiltroAuditoria = {}): Observable<Pagina<RegistroAuditoria>> {
    return this.http.get<Pagina<RegistroAuditoria>>(`${this.url}/registros`, { params: parametros(filtro) });
  }

  alertas(filtro: Omit<FiltroAuditoria, 'categoria' | 'actorId' | 'recursoId'> = {}): Observable<Pagina<RegistroAuditoria>> {
    return this.http.get<Pagina<RegistroAuditoria>>(`${this.url}/alertas`, { params: parametros(filtro) });
  }

  /** Recalcula la cadena de hashes (solo administrador). */
  integridad(): Observable<VerificacionAuditoria> {
    return this.http.get<VerificacionAuditoria>(`${this.url}/integridad`);
  }
}

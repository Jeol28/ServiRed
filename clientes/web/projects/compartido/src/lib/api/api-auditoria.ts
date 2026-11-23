import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../configuracion';
import { CategoriaAuditoria, Pagina, RegistroAuditoria, VerificacionAuditoria } from '../modelos';
import { parametros } from './parametros';

export type RolActorAuditoria = 'ADMINISTRADOR' | 'CIUDADANO' | 'OPERADOR' | 'REGULADOR' | 'SISTEMA';

/** Para buscar sin conocer identificadores: quién actuó y sobre qué. */
export const ROLES_ACTOR_AUDITORIA: { valor: RolActorAuditoria; nombre: string }[] = [
  { valor: 'ADMINISTRADOR', nombre: 'Administrador' },
  { valor: 'CIUDADANO', nombre: 'Ciudadano' },
  { valor: 'OPERADOR', nombre: 'Operador' },
  { valor: 'REGULADOR', nombre: 'Regulador' },
  { valor: 'SISTEMA', nombre: 'Sistema o anónimo' },
];
export const TIPOS_RECURSO_AUDITORIA: { valor: string; nombre: string }[] = [
  { valor: 'CASO', nombre: 'Caso' },
  { valor: 'CUENTA', nombre: 'Cuenta' },
  { valor: 'REPORTE', nombre: 'Reporte' },
  { valor: 'DENUNCIA', nombre: 'Denuncia' },
  { valor: 'EXPORTACION', nombre: 'Exportación' },
  { valor: 'SESION', nombre: 'Sesión' },
  { valor: 'ALERTA', nombre: 'Alerta' },
];

export interface FiltroAuditoria {
  categoria?: CategoriaAuditoria;
  accion?: string;
  /** Rol de quien actuó; SISTEMA = acciones del sistema o anónimas (sin rol). */
  actorRol?: RolActorAuditoria;
  /** Clase de recurso (CASO, CUENTA, REPORTE…). */
  recursoTipo?: string;
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

  alertas(filtro: Omit<FiltroAuditoria, 'categoria' | 'actorId' | 'recursoId' | 'actorRol' | 'recursoTipo'> = {}): Observable<Pagina<RegistroAuditoria>> {
    return this.http.get<Pagina<RegistroAuditoria>>(`${this.url}/alertas`, { params: parametros(filtro) });
  }

  /** Recalcula la cadena de hashes (solo administrador). */
  integridad(): Observable<VerificacionAuditoria> {
    return this.http.get<VerificacionAuditoria>(`${this.url}/integridad`);
  }
}

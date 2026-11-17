import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../configuracion';
import { Cuenta, NuevaCuentaPersonal, Pagina, Rol } from '../modelos';
import { parametros } from './parametros';

export interface CambiosCuenta {
  nombre?: string;
  activa?: boolean;
  idEmpresa?: string;
}

/** Administración de cuentas de personal (RF-11). Solo el administrador. */
@Injectable({ providedIn: 'root' })
export class ApiCuentas {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE)}/api/v1/cuentas`;

  listar(filtro: { rol?: Rol; pagina?: number; tamano?: number } = {}): Observable<Pagina<Cuenta>> {
    return this.http.get<Pagina<Cuenta>>(this.url, { params: parametros(filtro) });
  }

  crear(cuenta: NuevaCuentaPersonal): Observable<Cuenta> {
    return this.http.post<Cuenta>(this.url, cuenta);
  }

  cambiar(idCuenta: string, cambios: CambiosCuenta): Observable<Cuenta> {
    return this.http.patch<Cuenta>(`${this.url}/${idCuenta}`, cambios);
  }

  eliminar(idCuenta: string): Observable<void> {
    return this.http.delete<void>(`${this.url}/${idCuenta}`);
  }

  yo(): Observable<Cuenta> {
    return this.http.get<Cuenta>(`${this.url}/yo`);
  }
}

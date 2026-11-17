import { HttpBackend, HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, finalize, map, of, shareReplay, tap, throwError } from 'rxjs';
import { API_BASE } from '../configuracion';
import { ResumenCuenta, Rol, Sesion } from '../modelos';

const CLAVE_REFRESCO = 'servired.refresco';

/**
 * Sesión del usuario. Decisiones de seguridad (ADR-17):
 * - El token de acceso (JWT, 15 min) vive solo en memoria: no queda en disco ni
 *   lo puede leer otra pestaña.
 * - El token de refresco se guarda en sessionStorage para sobrevivir a una
 *   recarga; se borra al cerrar la pestaña y al cerrar sesión.
 * - El refresco es de un solo uso: las peticiones simultáneas comparten una
 *   única renovación y NUNCA se reintenta con el mismo token (el servidor lo
 *   trataría como robo y cerraría todas las sesiones).
 */
@Injectable({ providedIn: 'root' })
export class ServicioSesion {
  /** Cliente sin interceptores: las llamadas de autenticación no llevan JWT ni disparan renovaciones. */
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly base = inject(API_BASE);

  private readonly sesion = signal<Sesion | null>(null);
  private renovacion?: Observable<string>;

  readonly cuenta = computed<ResumenCuenta | null>(() => this.sesion()?.cuenta ?? null);
  readonly autenticado = computed(() => this.sesion() !== null);
  readonly rol = computed<Rol | null>(() => this.sesion()?.cuenta.rol ?? null);

  tokenAcceso(): string | null {
    return this.sesion()?.tokenAcceso ?? null;
  }

  tieneRol(...roles: Rol[]): boolean {
    const rol = this.rol();
    return rol !== null && roles.includes(rol);
  }

  iniciar(correo: string, clave: string): Observable<ResumenCuenta> {
    return this.http.post<Sesion>(`${this.base}/api/v1/auth/login`, { correo, clave }).pipe(
      tap((s) => this.guardar(s)),
      map((s) => s.cuenta),
    );
  }

  /** ¿Hay un token de refresco de una sesión anterior de esta pestaña? */
  puedeRestaurar(): boolean {
    return this.leerRefresco() !== null;
  }

  /** Renueva la sesión. Devuelve el nuevo token de acceso; si falla, la sesión termina. */
  renovar(): Observable<string> {
    if (this.renovacion) {
      return this.renovacion;
    }
    const refresco = this.leerRefresco();
    if (!refresco) {
      return throwError(() => new Error('Sin sesión'));
    }
    // Se retira antes de usarlo: pase lo que pase, este token no se vuelve a enviar.
    this.borrarRefresco();
    this.renovacion = this.http.post<Sesion>(`${this.base}/api/v1/auth/refrescar`, { tokenRefresco: refresco }).pipe(
      tap({ next: (s) => this.guardar(s), error: () => this.limpiar() }),
      map((s) => s.tokenAcceso),
      finalize(() => (this.renovacion = undefined)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.renovacion;
  }

  cerrar(): Observable<void> {
    const refresco = this.leerRefresco();
    this.limpiar();
    if (!refresco) {
      return of(undefined);
    }
    return this.http.post<void>(`${this.base}/api/v1/auth/cerrar-sesion`, { tokenRefresco: refresco });
  }

  /** Termina la sesión local sin llamar al servidor (p. ej. tras un 401 definitivo). */
  limpiar(): void {
    this.sesion.set(null);
    this.borrarRefresco();
  }

  private guardar(s: Sesion): void {
    this.sesion.set(s);
    try {
      sessionStorage.setItem(CLAVE_REFRESCO, s.tokenRefresco);
    } catch {
      // Sin almacenamiento (modo privado estricto): la sesión dura hasta recargar.
    }
  }

  private leerRefresco(): string | null {
    try {
      return sessionStorage.getItem(CLAVE_REFRESCO);
    } catch {
      return null;
    }
  }

  private borrarRefresco(): void {
    try {
      sessionStorage.removeItem(CLAVE_REFRESCO);
    } catch {
      // nada que borrar
    }
  }
}

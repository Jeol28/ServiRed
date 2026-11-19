import { HttpBackend, HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, finalize, from, map, of, shareReplay, switchMap, tap, throwError } from 'rxjs';
import { API_BASE } from '../configuracion';
import { ResumenCuenta, Rol, Sesion } from '../modelos';

/** Marca no secreta: «esta pestaña tenía sesión», para intentar restaurarla tras una recarga. */
const CLAVE_SESION = 'servired.sesion';
/** Pide al gateway que maneje el refresco en una cookie HttpOnly (ADR-30). */
const SESION_WEB = new HttpHeaders({ 'X-Sesion-Web': 'cookie' });
/** Candado entre pestañas: comparten la cookie y el refresco es de un solo uso. */
const CANDADO = 'servired-renovacion';

/**
 * Sesión del usuario. Decisiones de seguridad (ADR-17, ADR-30):
 * - El token de acceso (JWT, 15 min) vive solo en memoria.
 * - El token de refresco nunca llega a JavaScript: el gateway lo guarda en una cookie
 *   HttpOnly, SameSite=Strict, limitada a /api/v1/auth. Una XSS no puede leerlo.
 * - El refresco es de un solo uso: las renovaciones de esta pestaña comparten una sola
 *   petición, y las de distintas pestañas (que comparten la cookie) se turnan con un candado
 *   (Web Locks). Si dos lo usaran a la vez, el servidor lo trataría como robo y cerraría todas
 *   las sesiones.
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
    return this.http.post<Sesion>(`${this.base}/api/v1/auth/login`, { correo, clave }, { headers: SESION_WEB }).pipe(
      tap((s) => this.guardar(s)),
      map((s) => s.cuenta),
    );
  }

  /** ¿Esta pestaña tenía sesión? (la cookie no se puede leer: se intenta renovar). */
  puedeRestaurar(): boolean {
    try {
      return sessionStorage.getItem(CLAVE_SESION) !== null;
    } catch {
      return false;
    }
  }

  /** Renueva la sesión. Devuelve el nuevo token de acceso; si falla, la sesión termina. */
  renovar(): Observable<string> {
    if (this.renovacion) {
      return this.renovacion;
    }
    this.renovacion = this.conCandado(() =>
      this.http.post<Sesion>(`${this.base}/api/v1/auth/refrescar`, {}, { headers: SESION_WEB }),
    ).pipe(
      tap({ next: (s) => this.guardar(s), error: () => this.limpiar() }),
      map((s) => s.tokenAcceso),
      finalize(() => (this.renovacion = undefined)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.renovacion;
  }

  cerrar(): Observable<void> {
    const habia = this.autenticado() || this.puedeRestaurar();
    this.limpiar();
    if (!habia) {
      return of(undefined);
    }
    // El gateway revoca el refresco de la cookie en Identidad y la borra.
    return this.http.post<void>(`${this.base}/api/v1/auth/cerrar-sesion`, {}, { headers: SESION_WEB });
  }

  /** Termina la sesión local sin llamar al servidor (p. ej. tras un 401 definitivo). */
  limpiar(): void {
    this.sesion.set(null);
    try {
      sessionStorage.removeItem(CLAVE_SESION);
    } catch {
      // nada que borrar
    }
  }

  private guardar(s: Sesion): void {
    this.sesion.set(s);
    try {
      sessionStorage.setItem(CLAVE_SESION, '1');
    } catch {
      // Sin almacenamiento (modo privado estricto): tras recargar habrá que ingresar de nuevo.
    }
  }

  /** Ejecuta la renovación con el candado entre pestañas, si el navegador lo ofrece. */
  private conCandado<T>(peticion: () => Observable<T>): Observable<T> {
    const candados = typeof navigator !== 'undefined' ? navigator.locks : undefined;
    if (!candados) {
      return peticion();
    }
    return from(candados.request(CANDADO, () => new Promise<T>((resolver, rechazar) => {
      peticion().subscribe({ next: resolver, error: rechazar });
    })));
  }
}

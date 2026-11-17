import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { RUTA_INGRESO } from '../configuracion';
import { ServicioSesion } from '../sesion/servicio-sesion';

export const CABECERA_CORRELACION = 'X-Correlation-Id';

function esApi(req: HttpRequest<unknown>): boolean {
  return new URL(req.url, location.origin).pathname.startsWith('/api/');
}

/** Rutas donde nunca se envía el JWT: autenticación y denuncias anónimas (ADR-09). */
function esAnonima(req: HttpRequest<unknown>): boolean {
  const ruta = new URL(req.url, location.origin).pathname;
  return ruta.startsWith('/api/v1/auth/') || ruta.startsWith('/api/v1/denuncias');
}

/**
 * Agrega un X-Correlation-Id nuevo a cada petición a la API (RNF-OBS1). No se
 * envía en las denuncias anónimas: el gateway genera el suyo y así el navegador
 * no aporta ningún identificador.
 */
export const interceptorCorrelacion: HttpInterceptorFn = (req, next) => {
  if (!esApi(req) || esAnonima(req) || req.headers.has(CABECERA_CORRELACION)) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { [CABECERA_CORRELACION]: crypto.randomUUID() } }));
};

/**
 * Agrega el JWT a las peticiones a la API y, ante un 401, renueva la sesión una
 * sola vez y repite la petición. Si la renovación falla, termina la sesión y
 * lleva al ingreso.
 */
export const interceptorJwt: HttpInterceptorFn = (req, next) => {
  if (!esApi(req) || esAnonima(req)) {
    return next(req);
  }
  const sesion = inject(ServicioSesion);
  const router = inject(Router);
  const rutaIngreso = inject(RUTA_INGRESO);
  const conToken = (token: string | null) =>
    token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(conToken(sesion.tokenAcceso())).pipe(
    catchError((error: unknown) => {
      const expirada = error instanceof HttpErrorResponse && error.status === 401;
      if (!expirada || !sesion.puedeRestaurar()) {
        if (expirada && sesion.autenticado()) {
          sesion.limpiar();
          void router.navigateByUrl(rutaIngreso);
        }
        return throwError(() => error);
      }
      return sesion.renovar().pipe(
        catchError((fallo: unknown) => {
          void router.navigateByUrl(rutaIngreso);
          return throwError(() => fallo);
        }),
        switchMap((token) => next(conToken(token))),
      );
    }),
  );
};

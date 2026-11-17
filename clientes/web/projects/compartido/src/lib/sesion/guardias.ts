import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { RUTA_INGRESO } from '../configuracion';
import { Rol } from '../modelos';
import { ServicioSesion } from './servicio-sesion';

/**
 * Exige una sesión. Si la pestaña se recargó, intenta restaurarla con el token
 * de refresco antes de decidir.
 */
export const guardiaAutenticado: CanActivateFn = (_ruta, estado) => {
  const sesion = inject(ServicioSesion);
  const router = inject(Router);
  const ingreso = router.createUrlTree([inject(RUTA_INGRESO)], { queryParams: { volver: estado.url } });
  if (sesion.autenticado()) {
    return true;
  }
  if (!sesion.puedeRestaurar()) {
    return ingreso;
  }
  return sesion.renovar().pipe(
    map(() => true as const),
    catchError(() => of(ingreso)),
  );
};

/**
 * Exige uno de los roles. Es una ayuda de navegación: la autorización real la
 * aplican el gateway y cada servicio (un rol no permitido recibe 403).
 */
export function guardiaRol(...roles: Rol[]): CanActivateFn {
  return () => {
    const sesion = inject(ServicioSesion);
    return sesion.tieneRol(...roles) ? true : inject(Router).createUrlTree(['/sin-permiso']);
  };
}

import { InjectionToken } from '@angular/core';

/**
 * Base de la API. Los portales se sirven en el mismo origen que su proxy hacia
 * el gateway (nginx o `ng serve` con proxy), así que por defecto es relativa:
 * no hay CORS ni se expone ningún otro servicio.
 */
export const API_BASE = new InjectionToken<string>('API_BASE', { providedIn: 'root', factory: () => '' });

/** Ruta de la pantalla de ingreso de la app (para redirigir cuando la sesión expira). */
export const RUTA_INGRESO = new InjectionToken<string>('RUTA_INGRESO', { providedIn: 'root', factory: () => '/ingreso' });

import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { interceptorCorrelacion } from 'compartido';
import { routes } from './app.routes';

/** Portal público: no hay sesión ni JWT; solo consultas abiertas y denuncias anónimas. */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([interceptorCorrelacion])),
  ],
};

import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { interceptorCorrelacion, interceptorJwt } from 'compartido';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    // El orden importa: primero el correlationId y luego el JWT, para que un
    // reintento tras renovar la sesión conserve el mismo correlationId.
    provideHttpClient(withInterceptors([interceptorCorrelacion, interceptorJwt])),
  ],
};

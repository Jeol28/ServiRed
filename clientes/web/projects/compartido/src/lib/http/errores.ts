import { HttpErrorResponse } from '@angular/common/http';
import { Problema } from '../modelos';

/** Mensaje para el usuario a partir de un error HTTP con problem+json. */
export function mensajeDeError(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return 'Ocurrió un error inesperado.';
  }
  if (error.status === 0) {
    return 'No hay conexión con el servidor. Revise su red e intente de nuevo.';
  }
  const p = (error.error ?? {}) as Problema;
  const campos = p.errores?.map((e) => `${e.campo}: ${e.mensaje}`).join('; ');
  const base = campos || p.detail || p.title || `Error ${error.status}`;
  if (error.status === 429) {
    return 'Demasiadas solicitudes. Espere un momento e intente de nuevo.';
  }
  // El correlationId permite a soporte encontrar la petición en los logs.
  return error.status >= 500 && p.correlationId ? `${base} (referencia ${p.correlationId})` : base;
}

export function motivoDeError(error: unknown): string | undefined {
  return error instanceof HttpErrorResponse ? (error.error as Problema | null)?.motivo : undefined;
}

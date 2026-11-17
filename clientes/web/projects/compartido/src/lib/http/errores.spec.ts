import { HttpErrorResponse } from '@angular/common/http';
import { mensajeDeError, motivoDeError } from './errores';

const error = (status: number, cuerpo: unknown) => new HttpErrorResponse({ status, error: cuerpo });

describe('mensajeDeError', () => {
  it('sin conexión', () => {
    expect(mensajeDeError(error(0, null))).toContain('No hay conexión');
  });

  it('usa los errores de campo del problem+json', () => {
    const e = error(400, { title: 'Solicitud inválida', errores: [{ campo: 'nota', mensaje: 'muy corta' }] });
    expect(mensajeDeError(e)).toBe('nota: muy corta');
  });

  it('usa el detalle cuando no hay errores de campo', () => {
    expect(mensajeDeError(error(409, { detail: 'El caso ya está cerrado', motivo: 'TRANSICION' })))
      .toBe('El caso ya está cerrado');
    expect(motivoDeError(error(409, { motivo: 'VERSION' }))).toBe('VERSION');
  });

  it('en errores 5xx agrega la referencia del correlationId', () => {
    expect(mensajeDeError(error(503, { title: 'No disponible', correlationId: 'abc' }))).toBe('No disponible (referencia abc)');
  });

  it('429 tiene un mensaje propio', () => {
    expect(mensajeDeError(error(429, { title: 'Too Many Requests' }))).toContain('Demasiadas solicitudes');
  });

  it('errores que no son HTTP', () => {
    expect(mensajeDeError(new Error('x'))).toBe('Ocurrió un error inesperado.');
  });
});

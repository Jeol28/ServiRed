import { HttpParams } from '@angular/common/http';

/** Convierte un filtro en parámetros de consulta, omitiendo los vacíos. */
export function parametros(filtro: object = {}): HttpParams {
  let p = new HttpParams();
  for (const [clave, valor] of Object.entries(filtro)) {
    if (valor !== undefined && valor !== null && valor !== '' && valor !== false) {
      p = p.set(clave, String(valor));
    }
  }
  return p;
}

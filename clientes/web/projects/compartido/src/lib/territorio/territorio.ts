import { Injectable, signal } from '@angular/core';

export interface MunicipioCatalogo {
  /** Código DIVIPOLA de 5 dígitos. */
  codigo: string;
  nombre: string;
}

export interface DepartamentoCatalogo {
  /** Código DIVIPOLA de 2 dígitos. */
  codigo: string;
  nombre: string;
  municipios: MunicipioCatalogo[];
}

/**
 * Catálogo DIVIPOLA para las listas desplegables. Se descarga aparte (import
 * dinámico, ~24 kB) la primera vez que se pide, y queda en memoria.
 */
@Injectable({ providedIn: 'root' })
export class CatalogoTerritorial {
  readonly departamentos = signal<DepartamentoCatalogo[]>([]);
  private carga?: Promise<void>;

  cargar(): Promise<void> {
    this.carga ??= import('./divipola').then(({ DIVIPOLA }) => {
      this.departamentos.set(DIVIPOLA.map(([codigo, nombre, municipios]) => ({
        codigo,
        nombre,
        municipios: municipios.map(([sufijo, n]) => ({ codigo: codigo + sufijo, nombre: n })),
      })));
    });
    return this.carga;
  }
}

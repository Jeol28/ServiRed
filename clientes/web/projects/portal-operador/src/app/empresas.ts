import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiEmpresas, EmpresaPrestadora } from 'compartido';

/**
 * Empresas prestadoras reales (ADR-35) para mostrar nombres y elegir al crear operadores. Se
 * cargan una vez desde el catálogo; mientras llegan, o si un identificador no está, se muestra
 * el identificador tal cual.
 */
@Injectable({ providedIn: 'root' })
export class Empresas {
  private readonly api = inject(ApiEmpresas);
  private readonly lista = signal<EmpresaPrestadora[]>([]);
  private readonly porId = computed(() => new Map(this.lista().map((e) => [e.idEmpresa, e.nombre])));
  private pedida = false;

  /** Todas, por nombre. */
  readonly todas = this.lista.asReadonly();

  cargar(): void {
    if (this.pedida) {
      return;
    }
    this.pedida = true;
    this.api.listar().subscribe({
      next: (l) => this.lista.set(l),
      error: () => (this.pedida = false),
    });
  }

  nombre(id?: string | null): string {
    if (!id) {
      return 'Sin empresa';
    }
    this.cargar();
    return this.porId().get(id) ?? id;
  }

  /** Hasta `limite` empresas cuyo nombre o NIT contienen el texto (sin tildes ni mayúsculas). */
  buscar(texto: string, limite = 50): EmpresaPrestadora[] {
    this.cargar();
    return filtrarPorTexto(this.lista(), texto, limite);
  }
}

/** Las de la lista cuyo nombre o NIT contienen el texto (sin tildes ni mayúsculas), hasta `limite`. */
export function filtrarPorTexto(lista: EmpresaPrestadora[], texto: string, limite = 50): EmpresaPrestadora[] {
  const t = normalizar(texto);
  return (t ? lista.filter((e) => normalizar(e.nombre).includes(t) || (e.nit ?? '').includes(t)) : lista).slice(0, limite);
}

function normalizar(s: string): string {
  return s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
}

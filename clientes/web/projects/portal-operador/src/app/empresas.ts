/**
 * Empresas prestadoras de la semilla (V4__semilla_servicios_empresas.sql).
 * La API v1 no publica el catálogo de empresas; el portal solo las usa para
 * mostrar nombres y para que el administrador elija al enrutar o crear cuentas.
 * Un identificador desconocido se muestra tal cual.
 */
export const EMPRESAS: { id: string; nombre: string }[] = [
  { id: 'a0000000-0000-4000-8000-000000000001', nombre: 'Energía Andina (ejemplo)' },
  { id: 'a0000000-0000-4000-8000-000000000002', nombre: 'Aguas de la Sabana (ejemplo)' },
  { id: 'a0000000-0000-4000-8000-000000000003', nombre: 'Gases del Valle (ejemplo)' },
  { id: 'a0000000-0000-4000-8000-000000000004', nombre: 'Servicios Integrales del Caribe (ejemplo)' },
  { id: 'a0000000-0000-4000-8000-000000000005', nombre: 'Limpieza Urbana Colombia (ejemplo)' },
];

export function nombreEmpresa(id?: string | null): string {
  if (!id) {
    return 'Sin empresa';
  }
  return EMPRESAS.find((e) => e.id === id)?.nombre ?? id;
}

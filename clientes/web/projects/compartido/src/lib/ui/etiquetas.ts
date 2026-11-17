import { EstadoCaso, Magnitud, Prioridad, TipoServicio } from '../modelos';

export const NOMBRE_SERVICIO: Record<TipoServicio, string> = {
  ENERGIA: 'Energía', GAS: 'Gas', ACUEDUCTO: 'Acueducto', ALCANTARILLADO: 'Alcantarillado', ASEO: 'Aseo',
};

export const NOMBRE_ESTADO: Record<EstadoCaso, string> = {
  RECIBIDO: 'Recibido', EN_ATENCION: 'En atención', RESUELTO: 'Resuelto', CERRADO: 'Cerrado',
};

export const NOMBRE_PRIORIDAD: Record<Prioridad, string> = {
  BAJA: 'Baja', MEDIA: 'Media', ALTA: 'Alta', CRITICA: 'Crítica',
};

export const NOMBRE_MAGNITUD: Record<Magnitud, string> = {
  VIVIENDA: 'Una vivienda', CUADRA: 'Una cuadra', BARRIO: 'Un barrio', SECTOR: 'Un sector',
};

/** Colores por servicio, distinguibles entre sí y con contraste sobre el mapa. */
export const COLOR_SERVICIO: Record<TipoServicio, string> = {
  ENERGIA: '#d97706', GAS: '#dc2626', ACUEDUCTO: '#2563eb', ALCANTARILLADO: '#7c3aed', ASEO: '#059669',
};

export const COLOR_PRIORIDAD: Record<Prioridad, string> = {
  BAJA: '#64748b', MEDIA: '#2563eb', ALTA: '#d97706', CRITICA: '#dc2626',
};

/** Fecha y hora en la zona de Colombia. */
export function fechaCorta(iso?: string | null): string {
  if (!iso) {
    return '—';
  }
  return new Intl.DateTimeFormat('es-CO', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Bogota' })
    .format(new Date(iso));
}

/** Minutos como "2 h 15 min". */
export function duracion(minutos?: number | null): string {
  if (minutos === undefined || minutos === null) {
    return '—';
  }
  const m = Math.round(minutos);
  const h = Math.floor(m / 60);
  return h > 0 ? `${h} h ${m % 60} min` : `${m} min`;
}

/** Descarga un blob con un nombre de archivo. */
export function descargar(blob: Blob, nombre: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = nombre;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

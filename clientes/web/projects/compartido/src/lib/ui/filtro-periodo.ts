import { FiltroIndicadores } from '../modelos';

/** Valores de formulario (fechas YYYY-MM-DD) para filtros de periodo y territorio. */
export interface FormularioPeriodo {
  desde: string;
  hasta: string;
  tipoServicio?: FiltroIndicadores['tipoServicio'];
  codigoMunicipio: string;
  codigoDepartamento: string;
  situacion?: FiltroIndicadores['situacion'];
}

export function formularioPeriodo(dias = 30): FormularioPeriodo {
  const hasta = new Date();
  const desde = new Date(hasta.getTime() - dias * 86_400_000);
  return { desde: dia(desde), hasta: dia(hasta), codigoMunicipio: '', codigoDepartamento: '' };
}

/** Convierte el formulario en filtro de la API (fechas en hora de Colombia, UTC−5). */
export function filtroDePeriodo(f: FormularioPeriodo): FiltroIndicadores {
  return {
    desde: f.desde ? `${f.desde}T00:00:00-05:00` : undefined,
    hasta: f.hasta ? `${f.hasta}T23:59:59-05:00` : undefined,
    tipoServicio: f.tipoServicio,
    codigoMunicipio: f.codigoMunicipio.trim() || undefined,
    codigoDepartamento: f.codigoDepartamento.trim() || undefined,
    situacion: f.situacion,
  };
}

function dia(d: Date): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Bogota' }).format(d);
}

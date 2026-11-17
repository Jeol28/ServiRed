/**
 * Modelos del contrato público (contratos/api-publica/servired-api.v1.yaml).
 * Son la única fuente de tipos de los portales: no se inventan campos.
 */
export type Rol = 'CIUDADANO' | 'OPERADOR' | 'REGULADOR' | 'ADMINISTRADOR';
export type TipoServicio = 'ENERGIA' | 'GAS' | 'ACUEDUCTO' | 'ALCANTARILLADO' | 'ASEO';
export type Magnitud = 'VIVIENDA' | 'CUADRA' | 'BARRIO' | 'SECTOR';
export type EstadoCaso = 'RECIBIDO' | 'EN_ATENCION' | 'RESUELTO' | 'CERRADO';
export type Prioridad = 'BAJA' | 'MEDIA' | 'ALTA' | 'CRITICA';
export type EstadoModeracion = 'PENDIENTE' | 'VALIDADA' | 'DESCARTADA';
export type Situacion = 'ACTIVO' | 'RESUELTO';

export const TIPOS_SERVICIO: TipoServicio[] = ['ENERGIA', 'GAS', 'ACUEDUCTO', 'ALCANTARILLADO', 'ASEO'];
export const ESTADOS_CASO: EstadoCaso[] = ['RECIBIDO', 'EN_ATENCION', 'RESUELTO', 'CERRADO'];
export const PRIORIDADES: Prioridad[] = ['CRITICA', 'ALTA', 'MEDIA', 'BAJA'];

export interface Pagina<T> {
  elementos: T[];
  total: number;
  pagina: number;
  tamano: number;
}

export interface ResumenCuenta {
  idCuenta: string;
  rol: Rol;
  nombre?: string;
  idEmpresa?: string;
}

export interface Sesion {
  tokenAcceso: string;
  tipoToken: 'Bearer';
  expiraEn: number;
  tokenRefresco: string;
  cuenta: ResumenCuenta;
}

export interface Cuenta {
  idCuenta: string;
  correo: string;
  rol: Rol;
  idEmpresa?: string;
  nombre?: string;
  activa: boolean;
  creadaEn: string;
}

export interface NuevaCuentaPersonal {
  correo: string;
  clave: string;
  nombre?: string;
  rol: Rol;
  idEmpresa?: string;
}

export interface EntradaHistoriaCaso {
  estado: EstadoCaso;
  fecha: string;
  detalle: string;
  autor?: string;
}

export interface EvidenciaCaso {
  idEvidencia: string;
  tipoContenido: string;
  tamanoBytes: number;
  creadaEn: string;
}

export interface Caso {
  idCaso: string;
  idReporte: string;
  tipoServicio: TipoServicio;
  codigoMunicipio: string;
  nombreMunicipio?: string;
  magnitud: Magnitud;
  estado: EstadoCaso;
  abiertoEn: string;
  idEmpresa?: string;
  prioridad: Prioridad;
  prioridadManual: boolean;
  corteValidado: boolean;
  latitud?: number;
  longitud?: number;
  descripcion?: string;
  inicioEstimado?: string;
  asignadoA?: string;
  resolucion?: string;
  resueltoEn?: string;
  cerradoEn?: string;
  version: number;
  historia?: EntradaHistoriaCaso[];
  evidencias?: EvidenciaCaso[];
}

export interface FiltroCasos {
  estado?: EstadoCaso;
  tipoServicio?: TipoServicio;
  prioridad?: Prioridad;
  codigoMunicipio?: string;
  sinEmpresa?: boolean;
  mios?: boolean;
  pagina?: number;
  tamano?: number;
}

export interface Moderacion {
  idDenuncia: string;
  tipoServicio: TipoServicio;
  codigoMunicipio: string;
  nombreMunicipio?: string;
  latitud: number;
  longitud: number;
  descripcion: string;
  recibidaEn: string;
  idEmpresa?: string;
  estado: EstadoModeracion;
  nota?: string;
  moderadaPor?: string;
  moderadaEn?: string;
  version: number;
  evidencias: string[];
}

export interface PuntoOperativo {
  idReporte: string;
  tipoServicio: TipoServicio;
  latitud: number;
  longitud: number;
  magnitud?: Magnitud;
  estadoCaso?: EstadoCaso;
  prioridad?: Prioridad;
  validado: boolean;
  fechaRegistro: string;
  idEmpresa?: string;
}

// ------------------------------------------------------------- indicadores
export interface DefinicionIndicador {
  id: string;
  nombre: string;
  definicion: string;
  unidad: string;
  fuente: string;
}

export interface Metadatos {
  indicadores: DefinicionIndicador[];
  fuente: string;
  periodo: { desde: string; hasta: string };
  filtros: Record<string, string | undefined>;
  cobertura: { reportesEnVista: number; municipiosConReportes: number; primerReporte?: string; ultimoReporte?: string };
  actualizadoEn?: string;
  anonimizacion: string;
}

/** Si `suprimido` es true (menos de 3 reportes) no llega ningún valor. */
export interface ConjuntoIndicadores {
  suprimido: boolean;
  reportes?: number;
  duracion?: { promedio?: number; mediana?: number; p90?: number; reportesConDuracion: number };
  severidad?: { indice?: number; porMagnitud: Record<Magnitud, number>; porcentajeValidados?: number };
  atencion?: { porcentajeResueltos?: number; tiempoPromedioResolucionMinutos?: number };
}

export interface ResumenIndicadores {
  metadatos: Metadatos;
  indicadores: ConjuntoIndicadores;
  porServicio: { tipoServicio: TipoServicio; indicadores: ConjuntoIndicadores }[];
}

export interface IndicadoresTerritoriales {
  metadatos: Metadatos;
  nivel: 'DEPARTAMENTO' | 'MUNICIPIO';
  territorios: { codigo: string; nombre: string; departamento?: string; indicadores: ConjuntoIndicadores }[];
}

export interface MapaPublico {
  metadatos: Metadatos;
  celdaGrados: number;
  celdas: { latitud: number; longitud: number; reportes: number }[];
}

export interface FiltroIndicadores {
  desde?: string;
  hasta?: string;
  tipoServicio?: TipoServicio;
  codigoDepartamento?: string;
  codigoMunicipio?: string;
  situacion?: Situacion;
}

export interface CatalogoDatosAbiertos {
  conjuntos: { id: string; titulo: string; url: string; formatos: string[]; columnas: string[]; licencia: string }[];
  metadatos: Metadatos;
}

// --------------------------------------------------------------- auditoría
export type CategoriaAuditoria =
  | 'ACCESO' | 'ADMINISTRACION' | 'EXPORTACION' | 'MODERACION' | 'SEGURIDAD'
  | 'CAMBIO_ESTADO' | 'VALIDACION' | 'REPORTE' | 'ALERTA';

export const CATEGORIAS_AUDITORIA: CategoriaAuditoria[] = [
  'ACCESO', 'ADMINISTRACION', 'EXPORTACION', 'MODERACION', 'SEGURIDAD', 'CAMBIO_ESTADO', 'VALIDACION', 'REPORTE', 'ALERTA',
];

export interface RegistroAuditoria {
  secuencia: number;
  idEvento: string;
  tipo: string;
  origen: string;
  correlationId?: string;
  categoria: CategoriaAuditoria;
  accion: string;
  resultado?: 'EXITO' | 'FALLO';
  actorId?: string;
  actorRol?: string;
  recursoTipo?: string;
  recursoId?: string;
  detalle?: Record<string, unknown>;
  ocurridoEn: string;
  registradoEn: string;
  hash: string;
}

export interface VerificacionAuditoria {
  registros: number;
  integra: boolean;
  primeraRota?: number;
  ultimoHash?: string;
}

/** RFC 9457 Problem Details, como lo devuelven el gateway y los servicios. */
export interface Problema {
  title?: string;
  status?: number;
  detail?: string;
  correlationId?: string;
  motivo?: string;
  errores?: { campo: string; mensaje: string }[];
}

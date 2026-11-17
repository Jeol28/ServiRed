namespace ServiRed.Atencion.Dominio;

/// <summary>Servicios públicos domiciliarios (mismo catálogo que el contrato de eventos).</summary>
public enum TipoServicio { ENERGIA, GAS, ACUEDUCTO, ALCANTARILLADO, ASEO }

/// <summary>Área afectada según el ciudadano.</summary>
public enum Magnitud { VIVIENDA, CUADRA, BARRIO, SECTOR }

/// <summary>Ciclo de vida del caso para el operador (ADR-07).</summary>
public enum EstadoCaso { RECIBIDO, EN_ATENCION, RESUELTO, CERRADO }

/// <summary>Prioridad de atención (contrato caso.actualizado v1).</summary>
public enum Prioridad { BAJA, MEDIA, ALTA, CRITICA }

/// <summary>Resultado de moderar una denuncia anónima (RF-5).</summary>
public enum EstadoModeracion { PENDIENTE, VALIDADA, DESCARTADA }

/// <summary>Roles de SERVI-RED que llegan en el claim "rol" del JWT.</summary>
public enum Rol { CIUDADANO, OPERADOR, REGULADOR, ADMINISTRADOR }

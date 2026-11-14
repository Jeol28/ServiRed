namespace ServiRed.Atencion.Dominio;

/// <summary>Servicios públicos domiciliarios (mismo catálogo que el contrato de eventos).</summary>
public enum TipoServicio { ENERGIA, GAS, ACUEDUCTO, ALCANTARILLADO, ASEO }

/// <summary>Área afectada según el ciudadano.</summary>
public enum Magnitud { VIVIENDA, CUADRA, BARRIO, SECTOR }

/// <summary>Ciclo de vida del caso para el operador (ADR-07).</summary>
public enum EstadoCaso { RECIBIDO, EN_ATENCION, RESUELTO, CERRADO }

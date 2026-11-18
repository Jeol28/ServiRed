import Foundation

// DTO del contrato público v1. Las fechas viajan como texto ISO-8601 con zona.

struct UbicacionDTO: Codable, Sendable { var latitud: Double; var longitud: Double }

struct NuevoReporteDTO: Codable, Sendable, Equatable {
    var tipoServicio: TipoServicio
    var ubicacion: UbicacionDTO
    var inicioEstimado: String
    var magnitud: Magnitud
    var descripcion: String?
}

extension UbicacionDTO: Equatable {}

struct ConteoDTO: Codable, Sendable { var confirmaciones: Int; var controversias: Int; var confirmacionesNetas: Int }
struct ValidacionesDTO: Codable, Sendable { var corte: ConteoDTO?; var restablecimiento: ConteoDTO? }
struct EvidenciaDTO: Codable, Sendable { var idEvidencia: String; var tipoContenido: String; var tamanoBytes: Int; var creadaEn: String }
struct EntradaHistoriaDTO: Codable, Sendable { var estado: EstadoReporte; var fecha: String; var detalle: String? }

struct ReporteDTO: Codable, Sendable {
    var idReporte: String
    var tipoServicio: TipoServicio
    var ubicacion: UbicacionDTO
    var codigoMunicipio: String
    var nombreMunicipio: String?
    var inicioEstimado: String
    var magnitud: Magnitud
    var descripcion: String?
    var estado: EstadoReporte
    var fechaRegistro: String
    var fechaRestablecimiento: String?
    var duracionMinutos: Int?
    var validacion: ValidacionesDTO?
    var evidencias: [EvidenciaDTO]?
    var historia: [EntradaHistoriaDTO]?
}

struct PaginaReportesDTO: Codable, Sendable { var elementos: [ReporteDTO]; var total: Int; var pagina: Int; var tamano: Int }

struct ReporteCercanoDTO: Codable, Sendable {
    var idReporte: String
    var tipoServicio: TipoServicio
    var magnitud: Magnitud
    var estado: EstadoReporte
    var inicioEstimado: String
    var ubicacion: UbicacionDTO
    var distanciaMetros: Int
    var validacionCorte: ConteoDTO?
    var validacionRestablecimiento: ConteoDTO?
}

struct CeldaDTO: Codable, Sendable { var latitud: Double; var longitud: Double; var reportes: Int }
struct MapaPublicoDTO: Codable, Sendable { var celdas: [CeldaDTO] }

struct NuevaValidacionDTO: Codable, Sendable { var objeto: ObjetoValidacion; var sentido: Sentido; var ubicacion: UbicacionDTO }

struct ValidacionDTO: Codable, Sendable {
    var idReporte: String
    var objeto: ObjetoValidacion
    var confirmaciones: Int
    var controversias: Int
    var confirmacionesNetas: Int
    var validado: Bool
}

struct NuevoRestablecimientoDTO: Codable, Sendable { var fechaRestablecimiento: String; var comentario: String? }
struct NuevaDenunciaDTO: Codable, Sendable { var tipoServicio: TipoServicio; var ubicacion: UbicacionDTO; var descripcion: String }
struct CredencialesDTO: Codable, Sendable { var correo: String; var clave: String }

struct NuevoCiudadanoDTO: Codable, Sendable {
    var correo: String
    var clave: String
    var nombre: String?
    var aceptaTratamientoDatos: Bool
}

struct ResumenCuentaDTO: Codable, Sendable, Equatable { var idCuenta: String; var rol: String; var nombre: String? }

struct SesionDTO: Codable, Sendable {
    var tokenAcceso: String
    var tipoToken: String
    var expiraEn: Int
    var tokenRefresco: String
    var cuenta: ResumenCuentaDTO
}

struct SolicitudRefrescoDTO: Codable, Sendable { var tokenRefresco: String }
struct CuentaDTO: Codable, Sendable { var idCuenta: String; var correo: String; var rol: String; var nombre: String? }

struct CambiosCuentaPropiaDTO: Codable, Sendable {
    var nombre: String?
    var claveActual: String?
    var claveNueva: String?
}

struct ErrorCampoDTO: Codable, Sendable { var campo: String?; var mensaje: String? }

/// RFC 9457 Problem Details.
struct ProblemaDTO: Codable, Sendable {
    var title: String?
    var status: Int?
    var detail: String?
    var correlationId: String?
    var motivo: String?
    var errores: [ErrorCampoDTO]?
}

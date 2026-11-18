import Foundation

// Modelos del dominio de la app del ciudadano. Reflejan el contrato público
// (servired-api.v1.yaml); son los mismos de la app Android.

enum TipoServicio: String, CaseIterable, Codable, Sendable, Identifiable {
    case ENERGIA, GAS, ACUEDUCTO, ALCANTARILLADO, ASEO
    var id: String { rawValue }
    var etiqueta: String {
        switch self {
        case .ENERGIA: "Energía"
        case .GAS: "Gas"
        case .ACUEDUCTO: "Acueducto"
        case .ALCANTARILLADO: "Alcantarillado"
        case .ASEO: "Aseo"
        }
    }
}

enum Magnitud: String, CaseIterable, Codable, Sendable, Identifiable {
    case VIVIENDA, CUADRA, BARRIO, SECTOR
    var id: String { rawValue }
    var etiqueta: String {
        switch self {
        case .VIVIENDA: "Solo mi vivienda"
        case .CUADRA: "La cuadra"
        case .BARRIO: "El barrio"
        case .SECTOR: "Un sector amplio"
        }
    }
}

enum EstadoReporte: String, Codable, Sendable {
    case RECIBIDO, EN_ATENCION, RESTABLECIDO, CERRADO
    var etiqueta: String {
        switch self {
        case .RECIBIDO: "Recibido"
        case .EN_ATENCION: "En atención"
        case .RESTABLECIDO: "Restablecido"
        case .CERRADO: "Cerrado"
        }
    }
}

enum ObjetoValidacion: String, Codable, Sendable { case CORTE, RESTABLECIMIENTO }
enum Sentido: String, Codable, Sendable { case CONFIRMA, CONTROVIERTE }

struct Ubicacion: Equatable, Hashable, Sendable {
    var latitud: Double
    var longitud: Double
}

/// Conteo de validaciones comunitarias. Validado con 3 confirmaciones netas (ADR-08).
struct Conteo: Equatable, Sendable {
    var confirmaciones: Int
    var controversias: Int
    var netas: Int
    var validado: Bool { netas >= Reglas.confirmacionesParaValidar }
}

struct EntradaHistoria: Equatable, Sendable {
    var estado: EstadoReporte
    var fecha: Date
    var detalle: String?
}

struct Evidencia: Equatable, Sendable, Identifiable {
    var id: String
    var tipoContenido: String
    var tamanoBytes: Int
    var creadaEn: Date
}

struct Reporte: Equatable, Sendable, Identifiable {
    var id: String
    var tipoServicio: TipoServicio
    var ubicacion: Ubicacion
    var codigoMunicipio: String
    var nombreMunicipio: String?
    var inicioEstimado: Date
    var magnitud: Magnitud
    var descripcion: String?
    var estado: EstadoReporte
    var fechaRegistro: Date
    var fechaRestablecimiento: Date?
    var duracionMinutos: Int?
    var validacionCorte: Conteo?
    var validacionRestablecimiento: Conteo?
    var evidencias: [Evidencia]
    var historia: [EntradaHistoria]

    var puedeRestablecer: Bool { fechaRestablecimiento == nil && estado != .CERRADO }
    var puedeAdjuntarFoto: Bool { evidencias.count < Reglas.maxFotos && estado != .CERRADO }
}

/// Reporte de otro ciudadano a menos de 1 km: sin autor ni descripción.
struct ReporteCercano: Equatable, Sendable, Identifiable {
    var id: String
    var tipoServicio: TipoServicio
    var magnitud: Magnitud
    var estado: EstadoReporte
    var inicioEstimado: Date
    var ubicacion: Ubicacion
    var distanciaMetros: Int
    var corte: Conteo?
    var restablecimiento: Conteo?
}

/// Zona de ~1 km con cortes reportados, agregada y anónima (k ≥ 3): la vista nacional del mapa.
struct Zona: Equatable, Sendable {
    var ubicacion: Ubicacion
    var reportes: Int
}

struct ResultadoValidacion: Equatable, Sendable {
    var objeto: ObjetoValidacion
    var conteo: Conteo
    var nueva: Bool
}

struct Cuenta: Equatable, Sendable {
    var id: String
    var correo: String
    var nombre: String?
    var rol: String
}

struct NuevoReporte: Equatable, Sendable {
    var tipoServicio: TipoServicio
    var ubicacion: Ubicacion
    var inicioEstimado: Date
    var magnitud: Magnitud
    var descripcion: String?
}

struct NuevaDenuncia: Equatable, Sendable {
    var tipoServicio: TipoServicio
    var ubicacion: Ubicacion
    var descripcion: String
}

enum EstadoEnvio: String, Sendable { case PENDIENTE, ENVIANDO, FALLIDO }

/// Reporte guardado en el teléfono hasta que el servidor lo confirme. Su
/// `correlationId` se fija al crearlo y se reutiliza en cada reintento: es la
/// clave de idempotencia, así que reintentar nunca duplica el reporte.
struct ReportePendiente: Equatable, Sendable, Identifiable {
    var idLocal: UUID
    var correlationId: String
    var datos: NuevoReporte
    var fotos: [String]
    var estado: EstadoEnvio
    var error: String?
    var creadoEn: Date
    var idReporte: String?
    var id: UUID { idLocal }
}

enum EstadoSesion: Equatable, Sendable {
    case desconocida
    case anonima
    case autenticada(idCuenta: String, rol: String, nombre: String?)
}

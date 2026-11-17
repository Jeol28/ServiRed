import Foundation

enum TipoError: Sendable { case sinConexion, sesion, validacion, conflicto, noEncontrado, prohibido, limite, servidor }

/// Error de una operación, ya traducido a un mensaje para el ciudadano.
struct ErrorApp: Error, Equatable, Sendable, LocalizedError {
    var tipo: TipoError
    var mensaje: String
    var motivo: String? = nil

    var errorDescription: String? { mensaje }

    static let sinConexion = ErrorApp(tipo: .sinConexion, mensaje: "Sin conexión. Revise su red e intente de nuevo.")
    static let sesionVencida = ErrorApp(tipo: .sesion, mensaje: "Su sesión terminó. Ingrese de nuevo.")
    static func validacion(_ mensaje: String) -> ErrorApp { ErrorApp(tipo: .validacion, mensaje: mensaje) }

    /// Convierte cualquier error en un mensaje presentable.
    static func de(_ error: Error) -> ErrorApp {
        error as? ErrorApp ?? ErrorApp(tipo: .servidor, mensaje: "Ocurrió un error inesperado.")
    }
}

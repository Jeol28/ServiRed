import Foundation

// Puertos del dominio: los modelos de vista solo conocen estos protocolos. La
// capa de datos (URLSession, SwiftData, Keychain, CoreLocation) los implementa;
// las pruebas usan dobles en memoria.

@MainActor
protocol RepositorioSesion: AnyObject {
    var estado: EstadoSesion { get }
    /// Restaura la sesión guardada (refresco en el Keychain).
    func restaurar() async
    func ingresar(correo: String, clave: String) async throws
    func registrar(correo: String, clave: String, nombre: String?) async throws
    func cerrar() async
}

protocol RepositorioReportes: Sendable {
    func misReportes() async -> [Reporte]
    func pendientes() async -> [ReportePendiente]
    func actualizar() async throws
    func obtener(_ id: String) async throws -> Reporte
    /// Guarda el reporte en el teléfono y lo intenta enviar. Devuelve true si
    /// llegó al servidor y false si quedó pendiente (se enviará al haber red).
    func crear(_ nuevo: NuevoReporte, fotos: [String]) async throws -> Bool
    /// Envía lo pendiente. Devuelve false si algo debe reintentarse más tarde.
    @discardableResult func enviarPendientes() async -> Bool
    func descartarPendiente(_ idLocal: UUID) async
    func adjuntarFoto(idReporte: String, foto: String) async throws -> Reporte
    func foto(idReporte: String, idEvidencia: String) async throws -> Data
    func restablecer(_ id: String, fecha: Date, comentario: String?) async throws -> Reporte
    func cercanos(desde: Ubicacion) async throws -> [ReporteCercano]
    /// Zonas con cortes de los últimos 30 días en todo el país (agregadas y anónimas).
    func zonasPublicas() async throws -> [Zona]
    func validar(_ id: String, objeto: ObjetoValidacion, sentido: Sentido, desde: Ubicacion) async throws -> ResultadoValidacion
}

protocol RepositorioDenuncias: Sendable {
    func enviar(_ denuncia: NuevaDenuncia, foto: String?) async throws
}

protocol RepositorioCuenta: Sendable {
    func yo() async throws -> Cuenta
    func cambiarNombre(_ nombre: String) async throws -> Cuenta
    func cambiarClave(actual: String, nueva: String) async throws
    func eliminar() async throws
}

protocol ProveedorUbicacion: Sendable {
    /// Ubicación actual o nil si no hay permiso o señal.
    func actual() async -> Ubicacion?
}

/// Programa el envío de pendientes en segundo plano (BGTaskScheduler en la app).
protocol ProgramadorEnvio: Sendable {
    func programar()
}

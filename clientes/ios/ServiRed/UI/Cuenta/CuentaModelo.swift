import Foundation
import Observation

@MainActor
@Observable
final class CuentaModelo {
    private(set) var cuenta: Cuenta?
    var nombre = ""
    var claveActual = ""
    var claveNueva = ""
    private(set) var ocupado = false
    var error: String?
    var aviso: String?
    /// Mientras se intentan enviar los pendientes antes de cerrar sesión.
    private(set) var enviandoPendientes = false
    /// Por qué no se cerró la sesión: quedan reportes sin enviar (se borrarían del teléfono).
    var cierreBloqueado: String?

    private let repo: RepositorioCuenta
    private let sesion: RepositorioSesion
    private let reportes: RepositorioReportes

    init(repo: RepositorioCuenta, sesion: RepositorioSesion, reportes: RepositorioReportes) {
        self.repo = repo
        self.sesion = sesion
        self.reportes = reportes
    }

    func cargar() async {
        do {
            let c = try await repo.yo()
            cuenta = c
            nombre = c.nombre ?? ""
        } catch {
            self.error = ErrorApp.de(error).mensaje
        }
    }

    func guardarNombre() async {
        await ejecutar { [repo, nombre] in
            self.cuenta = try await repo.cambiarNombre(nombre)
            self.aviso = "Nombre actualizado."
        }
    }

    /// Si funciona, el servidor cierra todas las sesiones y la app vuelve al ingreso.
    func cambiarClave() async {
        await ejecutar { [repo, claveActual, claveNueva] in try await repo.cambiarClave(actual: claveActual, nueva: claveNueva) }
    }

    func eliminarCuenta() async { await ejecutar { [repo] in try await repo.eliminar() } }

    /// Cerrar sesión borra del teléfono los reportes que no se han enviado. Por eso primero
    /// se intentan enviar; si alguno queda (sin conexión o rechazado por el servidor), la
    /// sesión no se cierra y se explica por qué. Para renunciar a un reporte, el ciudadano lo
    /// descarta en Mis reportes: nunca se borra uno sin que lo decida.
    func cerrarSesion() async {
        await ejecutar { [sesion, reportes] in
            if await !reportes.pendientes().isEmpty {
                self.enviandoPendientes = true
                await reportes.enviarPendientes()
                self.enviandoPendientes = false
            }
            let quedan = await reportes.pendientes()
            if quedan.isEmpty {
                await sesion.cerrar()
            } else {
                self.cierreBloqueado = Self.motivoBloqueo(total: quedan.count,
                                                          rechazados: quedan.filter { $0.estado == .FALLIDO }.count)
            }
        }
    }

    static func motivoBloqueo(total: Int, rechazados: Int) -> String {
        let sinEnviar = total - rechazados
        var partes: [String] = []
        if sinEnviar == 1 { partes.append("1 aún no se pudo enviar (sin conexión o el servicio no respondió): inténtelo de nuevo con conexión") }
        if sinEnviar > 1 { partes.append("\(sinEnviar) aún no se pudieron enviar (sin conexión o el servicio no respondió): inténtelo de nuevo con conexión") }
        if rechazados == 1 { partes.append("1 fue rechazado por el servidor: revíselo en Mis reportes") }
        if rechazados > 1 { partes.append("\(rechazados) fueron rechazados por el servidor: revíselos en Mis reportes") }
        let inicio = total == 1 ? "Tiene 1 reporte sin enviar, que se borraría"
                                : "Tiene \(total) reportes sin enviar, que se borrarían"
        return "\(inicio) del teléfono al cerrar sesión. \(partes.joined(separator: "; ")). "
            + "Si ya no quiere enviar alguno, descártelo en Mis reportes."
    }

    private func ejecutar(_ bloque: () async throws -> Void) async {
        ocupado = true
        error = nil
        aviso = nil
        defer {
            ocupado = false
            claveActual = ""
            claveNueva = ""
        }
        do { try await bloque() } catch { self.error = ErrorApp.de(error).mensaje }
    }
}

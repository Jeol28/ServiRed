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

    private let repo: RepositorioCuenta
    private let sesion: RepositorioSesion

    init(repo: RepositorioCuenta, sesion: RepositorioSesion) {
        self.repo = repo
        self.sesion = sesion
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

    func cerrarSesion() async { await ejecutar { [sesion] in await sesion.cerrar() } }

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

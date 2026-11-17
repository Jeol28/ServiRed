import Foundation
import Observation

/// Reportes de otros ciudadanos a menos de 1 km para confirmarlos o controvertirlos (RF-4).
@MainActor
@Observable
final class CercanosModelo {
    private(set) var ubicacion: Ubicacion?
    private(set) var reportes: [ReporteCercano] = []
    private(set) var cargando = false
    private(set) var votando: String?
    var error: String?
    var aviso: String?

    private let repo: RepositorioReportes
    private let proveedor: ProveedorUbicacion

    init(repo: RepositorioReportes, ubicacion: ProveedorUbicacion) {
        self.repo = repo
        proveedor = ubicacion
    }

    func buscar() async {
        cargando = true
        error = nil
        aviso = nil
        defer { cargando = false }
        guard let u = await proveedor.actual() else {
            error = "Active la ubicación para ver los reportes cercanos."
            return
        }
        ubicacion = u
        do { reportes = try await repo.cercanos(desde: u) } catch { self.error = ErrorApp.de(error).mensaje }
    }

    func validar(_ id: String, objeto: ObjetoValidacion, sentido: Sentido) async {
        guard let u = ubicacion else { return }
        votando = id
        error = nil
        aviso = nil
        defer { votando = nil }
        do {
            let r = try await repo.validar(id, objeto: objeto, sentido: sentido, desde: u)
            if let i = reportes.firstIndex(where: { $0.id == id }) {
                if objeto == .CORTE { reportes[i].corte = r.conteo } else { reportes[i].restablecimiento = r.conteo }
            }
            aviso = r.nueva ? "Gracias: su validación quedó registrada." : "Ya había validado este reporte."
        } catch {
            self.error = ErrorApp.de(error).mensaje
        }
    }
}

import Foundation
import Observation

@MainActor
@Observable
final class MisReportesModelo {
    private(set) var reportes: [Reporte] = []
    private(set) var pendientes: [ReportePendiente] = []
    private(set) var actualizando = false
    /// Sin red se muestran los reportes guardados en el teléfono.
    private(set) var sinConexion = false
    private(set) var error: String?

    private let repo: RepositorioReportes

    init(repo: RepositorioReportes) { self.repo = repo }

    func cargarLocal() async {
        reportes = await repo.misReportes()
        pendientes = await repo.pendientes()
    }

    func actualizar() async {
        guard !actualizando else { return }
        actualizando = true
        defer { actualizando = false }
        await repo.enviarPendientes()
        do {
            try await repo.actualizar()
            sinConexion = false
            error = nil
        } catch {
            let e = ErrorApp.de(error)
            sinConexion = e.tipo == .sinConexion
            self.error = e.tipo == .sinConexion ? nil : e.mensaje
        }
        await cargarLocal()
    }

    func descartar(_ idLocal: UUID) async {
        await repo.descartarPendiente(idLocal)
        await cargarLocal()
    }
}

enum ResultadoEnvio: Equatable { case enviado, pendiente }

/// Formulario de un reporte nuevo (RF-1, RF-2).
@MainActor
@Observable
final class NuevoReporteModelo {
    var tipo: TipoServicio?
    var magnitud: Magnitud?
    /// Hace cuánto empezó el corte.
    var minutosAtras = 0
    var ubicacion: Ubicacion?
    private(set) var buscandoUbicacion = false
    var descripcion = ""
    private(set) var fotos: [String] = []
    private(set) var enviando = false
    var error: String?
    private(set) var resultado: ResultadoEnvio?

    private let repo: RepositorioReportes
    private let proveedor: ProveedorUbicacion
    private let descartarFoto: (String) -> Void
    private let ahora: () -> Date

    init(repo: RepositorioReportes, ubicacion: ProveedorUbicacion, descartarFoto: @escaping (String) -> Void = { _ in },
         ahora: @escaping () -> Date = Date.init) {
        self.repo = repo
        proveedor = ubicacion
        self.descartarFoto = descartarFoto
        self.ahora = ahora
    }

    func usarMiUbicacion() async {
        buscandoUbicacion = true
        let u = await proveedor.actual()
        buscandoUbicacion = false
        if let u { ubicacion = u } else if ubicacion == nil { error = "No se pudo obtener su ubicación. Márquela en el mapa." }
    }

    func agregarFoto(_ nombre: String) {
        guard fotos.count < Reglas.maxFotos else {
            descartarFoto(nombre)
            error = "Máximo \(Reglas.maxFotos) fotos."
            return
        }
        fotos.append(nombre)
        error = nil
    }

    func quitarFoto(_ nombre: String) {
        descartarFoto(nombre)
        fotos.removeAll { $0 == nombre }
    }

    func enviar() async {
        guard let tipo else { error = "Elija el servicio afectado."; return }
        guard let magnitud else { error = "Indique a cuántos afecta el corte."; return }
        guard let ubicacion else { error = "Indique dónde ocurre el corte."; return }
        let nuevo = NuevoReporte(tipoServicio: tipo, ubicacion: ubicacion,
                                 inicioEstimado: ahora().addingTimeInterval(-Double(minutosAtras) * 60),
                                 magnitud: magnitud, descripcion: descripcion)
        if let e = Reglas.validarReporte(nuevo, ahora: ahora()).first { error = e; return }
        enviando = true
        defer { enviando = false }
        do {
            resultado = try await repo.crear(nuevo, fotos: fotos) ? .enviado : .pendiente
        } catch {
            self.error = ErrorApp.de(error).mensaje
        }
    }
}

/// Estado del reporte, historia, fotos y registro del restablecimiento (RF-3).
@MainActor
@Observable
final class DetalleReporteModelo {
    private(set) var reporte: Reporte?
    private(set) var cargando = true
    private(set) var ocupado = false
    var error: String?
    var aviso: String?
    private(set) var fotos: [String: Data] = [:]
    var minutosRestablecido = 0
    var comentario = ""

    let id: String
    private let repo: RepositorioReportes
    private let ahora: () -> Date

    init(id: String, repo: RepositorioReportes, ahora: @escaping () -> Date = Date.init) {
        self.id = id
        self.repo = repo
        self.ahora = ahora
    }

    func cargar() async {
        do { reporte = try await repo.obtener(id) } catch { self.error = ErrorApp.de(error).mensaje }
        cargando = false
    }

    func verFoto(_ idEvidencia: String) async {
        do { fotos[idEvidencia] = try await repo.foto(idReporte: id, idEvidencia: idEvidencia) } catch { self.error = ErrorApp.de(error).mensaje }
    }

    func restablecer() async {
        let fecha = ahora().addingTimeInterval(-Double(minutosRestablecido) * 60)
        let texto = comentario
        await ejecutar("Registramos que el servicio volvió. ¡Gracias!") { [repo, id] in
            try await repo.restablecer(id, fecha: fecha, comentario: texto)
        }
    }

    func adjuntar(_ foto: String) async {
        await ejecutar("Foto agregada.") { [repo, id] in try await repo.adjuntarFoto(idReporte: id, foto: foto) }
    }

    private func ejecutar(_ exito: String, _ accion: () async throws -> Reporte) async {
        ocupado = true
        error = nil
        aviso = nil
        defer { ocupado = false }
        do {
            reporte = try await accion()
            aviso = exito
            comentario = ""
        } catch {
            self.error = ErrorApp.de(error).mensaje
        }
    }
}

import Foundation
import Observation

/// Marca seleccionada en el mapa: un corte cercano o un reporte propio.
enum Seleccion: Equatable {
    case cercano(String)
    case propio(String)
}

/// Pantalla principal de la app (igual que en Android): el mapa con los cortes
/// cercanos para confirmarlos, los reportes propios y las zonas con cortes del país.
/// Sin cuenta (`anonimo`) solo muestra las zonas agregadas: los cortes exactos son
/// para quien tiene cuenta (RNF-DAT2).
@MainActor
@Observable
final class MapaModelo {
    private(set) var ubicacion: Ubicacion?
    private(set) var cercanos: [ReporteCercano] = []
    private(set) var mios: [Reporte] = []
    private(set) var pendientes = 0
    private(set) var zonas: [Zona] = []
    private(set) var seleccion: Seleccion?
    /// Reportes propios a los que ya se respondió "todavía no" en esta sesión.
    private(set) var descartados: Set<String> = []
    private(set) var cargando = false
    private(set) var ocupado = false
    private(set) var sinUbicacion = false
    var aviso: String?
    var error: String?
    /// Cambia para volver a centrar el mapa en el ciudadano.
    private(set) var recentrar = 0

    private let repo: RepositorioReportes
    private let proveedor: ProveedorUbicacion
    private let ahora: () -> Date
    let anonimo: Bool

    init(repo: RepositorioReportes, ubicacion: ProveedorUbicacion, ahora: @escaping () -> Date = Date.init, anonimo: Bool = false) {
        self.repo = repo
        proveedor = ubicacion
        self.ahora = ahora
        self.anonimo = anonimo
    }

    /// Reportes propios con el servicio todavía caído.
    var misActivos: [Reporte] { mios.filter(\.puedeRestablecer) }

    var cercanoSeleccionado: ReporteCercano? {
        if case .cercano(let id) = seleccion { return cercanos.first { $0.id == id } }
        return nil
    }

    var propioSeleccionado: Reporte? {
        if case .propio(let id) = seleccion { return mios.first { $0.id == id } }
        return nil
    }

    /// Si nada está seleccionado, se pregunta por el primer corte propio activo: "¿Ya volvió la luz?".
    var preguntaVolvio: Reporte? {
        seleccion == nil ? misActivos.first { !descartados.contains($0.id) } : nil
    }

    func cargarLocal() async {
        mios = await repo.misReportes()
        pendientes = await repo.pendientes().count
    }

    /// Ubicación, cortes cercanos, reportes propios (y pendientes) y zonas nacionales.
    func refrescar() async {
        guard !cargando else { return }
        cargando = true
        defer { cargando = false }
        let u = await proveedor.actual() ?? ubicacion
        ubicacion = u
        sinUbicacion = u == nil
        if let u, !anonimo {
            do {
                cercanos = try await repo.cercanos(desde: u)
                error = nil
            } catch {
                let e = ErrorApp.de(error)
                self.error = e.tipo == .sinConexion ? "Sin conexión: mostramos lo último que tenemos." : e.mensaje
            }
        }
        if !anonimo {
            await repo.enviarPendientes()
            try? await repo.actualizar()
        }
        if let z = try? await repo.zonasPublicas() { zonas = z }
        await cargarLocal()
    }

    func volverACentrar() async {
        recentrar += 1
        await refrescar()
    }

    /// `idMarca` es el id de la marca del mapa: "c:<id>" (cercano) o "m:<id>" (propio).
    func seleccionar(_ idMarca: String?) {
        aviso = nil
        error = nil
        guard let idMarca else { seleccion = nil; return }
        if idMarca.hasPrefix("c:") { seleccion = .cercano(String(idMarca.dropFirst(2))) }
        else if idMarca.hasPrefix("m:") { seleccion = .propio(String(idMarca.dropFirst(2))) }
        else { seleccion = nil }
    }

    /// Confirmar ("también me afecta") o controvertir ("aquí no") el corte cercano seleccionado.
    func validar(_ sentido: Sentido, objeto: ObjetoValidacion = .CORTE) async {
        guard let c = cercanoSeleccionado, let u = ubicacion else { return }
        ocupado = true
        error = nil
        defer { ocupado = false }
        do {
            let r = try await repo.validar(c.id, objeto: objeto, sentido: sentido, desde: u)
            if let i = cercanos.firstIndex(where: { $0.id == c.id }) {
                if objeto == .CORTE { cercanos[i].corte = r.conteo } else { cercanos[i].restablecimiento = r.conteo }
            }
            seleccion = nil
            aviso = !r.nueva ? "Ya habías respondido por este corte."
                : sentido == .CONFIRMA ? "¡Gracias! Tu confirmación ayuda a priorizar este corte." : "Gracias, lo tendremos en cuenta."
        } catch {
            self.error = ErrorApp.de(error).mensaje
        }
    }

    /// Un toque: el servicio de un reporte propio volvió ahora.
    func yaVolvio(_ idReporte: String) async {
        ocupado = true
        error = nil
        defer { ocupado = false }
        do {
            _ = try await repo.restablecer(idReporte, fecha: ahora(), comentario: nil)
            seleccion = nil
            aviso = "¡Qué bien! Registramos que el servicio volvió."
            await cargarLocal()
        } catch {
            self.error = ErrorApp.de(error).mensaje
        }
    }

    func todaviaNo(_ idReporte: String) {
        descartados.insert(idReporte)
        seleccion = nil
    }

    func avisar(_ texto: String) {
        aviso = texto
        error = nil
    }

    func cerrarAviso() {
        aviso = nil
        error = nil
    }
}

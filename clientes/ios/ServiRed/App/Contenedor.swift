import Foundation
import SwiftData

/// Inyección de dependencias manual: una instancia de cada pieza para toda la app.
@MainActor
final class Contenedor {
    let sesion: SesionServiRed
    let reportes: RepositorioReportes
    let denuncias: RepositorioDenuncias
    let cuenta: RepositorioCuenta
    let ubicacion: ProveedorUbicacion
    let fotos: PreparadorFotos
    private var monitor: MonitorRed?

    static let compartido = Contenedor()

    init(base: URL = Contenedor.baseConfigurada(), cofre: CofreTokens = CofreKeychain()) {
        let transporte = TransporteURLSession()
        let carpeta = CarpetaFotos.predeterminada()
        let modelo = (try? AlmacenLocal.contenedor()) ?? (try! AlmacenLocal.contenedor(enMemoria: true))
        let almacen = AlmacenLocal(modelContainer: modelo)
        let sesion = SesionServiRed(base: base, transporte: transporte, cofre: cofre) {
            // Al cerrar sesión no quedan datos personales en el teléfono.
            await almacen.borrarTodo()
            carpeta.borrarTodo()
        }
        let api = ClienteAPI(base: base, transporte: transporte, gestor: sesion.gestor)
        let reportes = RepositorioReportesImpl(api: api, almacen: almacen, fotos: carpeta, programador: EnvioEnSegundoPlano())
        self.sesion = sesion
        self.reportes = reportes
        denuncias = RepositorioDenunciasImpl(publica: ClienteAPI(base: base, transporte: transporte, gestor: nil), fotos: carpeta)
        cuenta = RepositorioCuentaImpl(api: api) { [weak sesion] in await sesion?.cerrar() }
        ubicacion = ProveedorUbicacionCoreLocation()
        fotos = PreparadorFotos(carpeta: carpeta)
        monitor = MonitorRed { Task { await reportes.enviarPendientes() } }
    }

    /// Base del gateway desde el Info.plist (API_BASE en los xcconfig: por defecto, el servidor
    /// en línea). En Debug, SERVIRED_API_BASE la reemplaza: así las pruebas de interfaz y de
    /// punta a punta van al compose local y nunca crean datos en el servidor en línea.
    nonisolated static func baseConfigurada() -> URL {
        #if DEBUG
        if let propia = ProcessInfo.processInfo.environment["SERVIRED_API_BASE"], let url = URL(string: propia) {
            return url
        }
        #endif
        let texto = Bundle.main.object(forInfoDictionaryKey: "ServiRedAPIBase") as? String ?? "https://servired.taile94c21.ts.net/"
        return URL(string: texto)!
    }
}

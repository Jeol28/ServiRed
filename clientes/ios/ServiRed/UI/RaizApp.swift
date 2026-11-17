import SwiftUI

enum Ruta: Hashable {
    case detalle(String)
    case nuevo
}

/**
 Raíz de la app. Sin sesión: ingreso y denuncia anónima. Con sesión: las
 pestañas del ciudadano. Al cambiar la sesión se descarta toda la navegación.
 */
struct RaizApp: View {
    let c: Contenedor

    var body: some View {
        Group {
            switch c.sesion.estado {
            case .desconocida:
                ProgressView()
            case .anonima:
                SinSesion(c: c)
            case .autenticada(let idCuenta, _, _):
                Pestanas(c: c).id(idCuenta)
            }
        }
        .task { await c.sesion.restaurar() }
    }
}

private struct SinSesion: View {
    let c: Contenedor
    @State private var denunciar = false

    var body: some View {
        NavigationStack {
            IngresoVista(modelo: IngresoModelo(sesion: c.sesion)) { denunciar = true }
                .navigationDestination(isPresented: $denunciar) {
                    DenunciaVista(modelo: DenunciaModelo(repo: c.denuncias, ubicacion: c.ubicacion, descartarFoto: c.fotos.descartar),
                                  fotos: c.fotos)
                }
        }
    }
}

private struct Pestanas: View {
    let c: Contenedor
    @State private var camino = NavigationPath()

    var body: some View {
        TabView {
            Tab("Mis reportes", systemImage: "house") {
                NavigationStack(path: $camino) {
                    MisReportesVista(modelo: MisReportesModelo(repo: c.reportes)) { camino.append(Ruta.nuevo) }
                        .navigationDestination(for: Ruta.self) { ruta in
                            switch ruta {
                            case .detalle(let id):
                                DetalleReporteVista(modelo: DetalleReporteModelo(id: id, repo: c.reportes), fotos: c.fotos)
                            case .nuevo:
                                NuevoReporteVista(modelo: NuevoReporteModelo(repo: c.reportes, ubicacion: c.ubicacion,
                                                                             descartarFoto: c.fotos.descartar), fotos: c.fotos)
                            }
                        }
                }
            }
            Tab("Cercanos", systemImage: "location") {
                NavigationStack { CercanosVista(modelo: CercanosModelo(repo: c.reportes, ubicacion: c.ubicacion)) }
            }
            Tab("Denunciar", systemImage: "exclamationmark.triangle") {
                NavigationStack {
                    DenunciaVista(modelo: DenunciaModelo(repo: c.denuncias, ubicacion: c.ubicacion, descartarFoto: c.fotos.descartar),
                                  fotos: c.fotos)
                }
            }
            Tab("Cuenta", systemImage: "person") {
                NavigationStack { CuentaVista(modelo: CuentaModelo(repo: c.cuenta, sesion: c.sesion)) }
            }
        }
    }
}

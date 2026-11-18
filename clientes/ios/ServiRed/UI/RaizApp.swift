import SwiftUI

enum Ruta: Hashable {
    case detalle(String)
    case misReportes
    case denuncia
    case cuenta
}

/**
 Raíz de la app. Sin sesión: ingreso y denuncia anónima. Con sesión: el mapa
 como pantalla principal y un menú lateral con lo demás (igual que en Android).
 Al cambiar la sesión se descarta toda la navegación.
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
            case .autenticada(let idCuenta, _, let nombre):
                ConSesion(c: c, nombre: nombre).id(idCuenta)
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

private struct ConSesion: View {
    let c: Contenedor
    let nombre: String?
    @State private var camino = NavigationPath()
    @State private var menuAbierto = false
    @State private var mapa: MapaModelo?

    var body: some View {
        NavigationStack(path: $camino) {
            Group {
                if let mapa {
                    MapaVista(modelo: mapa, c: c, alAbrirMenu: { withAnimation(.snappy) { menuAbierto = true } },
                              alVerDetalle: { camino.append(Ruta.detalle($0)) })
                }
            }
            .navigationDestination(for: Ruta.self) { ruta in
                switch ruta {
                case .detalle(let id):
                    DetalleReporteVista(modelo: DetalleReporteModelo(id: id, repo: c.reportes), fotos: c.fotos)
                case .misReportes:
                    MisReportesVista(modelo: MisReportesModelo(repo: c.reportes))
                case .denuncia:
                    DenunciaVista(modelo: DenunciaModelo(repo: c.denuncias, ubicacion: c.ubicacion, descartarFoto: c.fotos.descartar),
                                  fotos: c.fotos)
                case .cuenta:
                    CuentaVista(modelo: CuentaModelo(repo: c.cuenta, sesion: c.sesion))
                }
            }
        }
        .overlay { if menuAbierto { MenuLateral(nombre: nombre, cerrar: cerrarMenu, ir: ir) } }
        .onAppear { if mapa == nil { mapa = MapaModelo(repo: c.reportes, ubicacion: c.ubicacion) } }
    }

    private func cerrarMenu() { withAnimation(.snappy) { menuAbierto = false } }

    private func ir(_ ruta: Ruta?) {
        cerrarMenu()
        camino = NavigationPath()
        if let ruta { camino.append(ruta) }
    }
}

/// Menú lateral: se desliza desde la izquierda sobre el mapa.
private struct MenuLateral: View {
    let nombre: String?
    let cerrar: () -> Void
    let ir: (Ruta?) -> Void

    var body: some View {
        ZStack(alignment: .leading) {
            Color.black.opacity(0.35).ignoresSafeArea().onTapGesture(perform: cerrar)
                .accessibilityLabel("Cerrar menú").accessibilityAddTraits(.isButton)
            VStack(alignment: .leading, spacing: 4) {
                Text("SERVI-RED").font(.title2.bold()).foregroundStyle(Color.accentColor)
                Text(nombre.map { "Hola, \($0)" } ?? "Reporte cortes de servicios públicos").font(.subheadline)
                    .padding(.bottom, 16)
                Elemento(texto: "Mapa", icono: "map") { ir(nil) }
                Elemento(texto: "Mis reportes", icono: "list.bullet") { ir(.misReportes) }
                Elemento(texto: "Denuncia anónima", icono: "exclamationmark.triangle") { ir(.denuncia) }
                Elemento(texto: "Mi cuenta", icono: "person") { ir(.cuenta) }
                Spacer()
            }
            .padding(24)
            .frame(width: 300, alignment: .leading)
            .frame(maxHeight: .infinity)
            .background(.background)
            .transition(.move(edge: .leading))
        }
    }

    private struct Elemento: View {
        let texto: String
        let icono: String
        let accion: () -> Void

        var body: some View {
            Button(action: accion) {
                Label(texto, systemImage: icono).font(.body.weight(.medium)).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 12)
            }
            .foregroundStyle(.primary)
        }
    }
}

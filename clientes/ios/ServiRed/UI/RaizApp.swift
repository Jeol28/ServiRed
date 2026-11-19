import SwiftUI

enum Ruta: Hashable {
    case detalle(String)
    case misReportes
    case denuncia
    case cuenta
    case ingreso(registro: Bool)
}

/**
 Raíz de la app (igual que en Android). Siempre abre en el mapa, con o sin
 cuenta. Sin cuenta se ven las zonas del país y se puede denunciar de forma
 anónima; reportar pide crear la cuenta. Al cambiar la sesión se descarta toda
 la navegación.
 */
struct RaizApp: View {
    let c: Contenedor

    var body: some View {
        Group {
            switch c.sesion.estado {
            case .desconocida:
                ProgressView()
            case .anonima:
                Principal(c: c, nombre: nil, anonimo: true).id("anonima")
            case .autenticada(let idCuenta, _, let nombre):
                Principal(c: c, nombre: nombre, anonimo: false).id(idCuenta)
            }
        }
        .task { await c.sesion.restaurar() }
    }
}

private struct Principal: View {
    let c: Contenedor
    let nombre: String?
    let anonimo: Bool
    @State private var camino = NavigationPath()
    @State private var menuAbierto = false
    @State private var mapa: MapaModelo?

    var body: some View {
        NavigationStack(path: $camino) {
            Group {
                if let mapa {
                    MapaVista(modelo: mapa, c: c, alAbrirMenu: { withAnimation(.snappy) { menuAbierto = true } },
                              alVerDetalle: { camino.append(Ruta.detalle($0)) },
                              alIngresar: { camino.append(Ruta.ingreso(registro: $0)) },
                              alDenunciar: { camino.append(Ruta.denuncia) })
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
                    CuentaVista(modelo: CuentaModelo(repo: c.cuenta, sesion: c.sesion, reportes: c.reportes))
                case .ingreso(let registro):
                    IngresoVista(modelo: IngresoModelo(sesion: c.sesion, registro: registro)) { camino.append(Ruta.denuncia) }
                }
            }
        }
        .overlay { if menuAbierto { MenuLateral(nombre: nombre, anonimo: anonimo, cerrar: cerrarMenu, ir: ir) } }
        .onAppear { if mapa == nil { mapa = MapaModelo(repo: c.reportes, ubicacion: c.ubicacion, anonimo: anonimo) } }
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
    let anonimo: Bool
    let cerrar: () -> Void
    let ir: (Ruta?) -> Void

    var body: some View {
        ZStack(alignment: .leading) {
            Color.black.opacity(0.35).ignoresSafeArea().onTapGesture(perform: cerrar)
                .accessibilityLabel("Cerrar menú").accessibilityAddTraits(.isButton)
            VStack(alignment: .leading, spacing: 4) {
                Text("SERVI-RED").font(.title2.bold()).foregroundStyle(Color.accentColor)
                Text(anonimo ? "Sin cuenta: explore el mapa y denuncie de forma anónima"
                     : nombre.map { "Hola, \($0)" } ?? "Reporte cortes de servicios públicos").font(.subheadline)
                    .padding(.bottom, 16)
                Elemento(texto: "Mapa", icono: "map") { ir(nil) }
                if !anonimo { Elemento(texto: "Mis reportes", icono: "list.bullet") { ir(.misReportes) } }
                Elemento(texto: "Denuncia anónima", icono: "exclamationmark.triangle") { ir(.denuncia) }
                if anonimo {
                    Elemento(texto: "Ingresar o crear cuenta", icono: "person") { ir(.ingreso(registro: false)) }
                } else {
                    Elemento(texto: "Mi cuenta", icono: "person") { ir(.cuenta) }
                }
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

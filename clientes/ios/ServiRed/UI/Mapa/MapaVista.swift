import SwiftUI

/// Ámbar del botón de reportar: se distingue del mapa en claro y en oscuro.
private let ambar = Color(red: 0.96, green: 0.62, blue: 0.04)

/// Pantalla principal: el mapa a pantalla completa con el botón grande de reportar,
/// las tarjetas de los cortes y el acceso al menú (igual que en Android).
struct MapaVista: View {
    @State var modelo: MapaModelo
    let c: Contenedor
    let alAbrirMenu: () -> Void
    let alVerDetalle: (String) -> Void
    /// Sin cuenta: ir al ingreso (`true` abre directamente el registro).
    var alIngresar: (Bool) -> Void = { _ in }
    var alDenunciar: () -> Void = {}

    @State private var reporte: NuevoReporteModelo?
    @State private var hojaVisible = false
    @State private var invitacionVisible = false
    @State private var eligiendo = false
    @State private var centroMapa: Ubicacion?

    private var marcas: [MarcaMapa] {
        modelo.cercanos.map { MarcaMapa(id: "c:\($0.id)", ubicacion: $0.ubicacion, colorHex: $0.tipoServicio.hex, icono: $0.tipoServicio.emoji) }
            + modelo.misActivos.map {
                MarcaMapa(id: "m:\($0.id)", ubicacion: $0.ubicacion, colorHex: $0.tipoServicio.hex, icono: $0.tipoServicio.emoji, propio: true)
            }
    }

    var body: some View {
        ZStack {
            MapaOSM(marcas: eligiendo ? [] : marcas, zonas: modelo.zonas, usuario: modelo.ubicacion, centro: modelo.ubicacion,
                    zoom: 15.5, recentrar: modelo.recentrar,
                    alElegir: { _ in modelo.seleccionar(nil) },
                    alTocarMarca: { modelo.seleccionar($0) },
                    alMoverCentro: { centroMapa = $0 })
                .ignoresSafeArea()

            if eligiendo {
                // Pin fijo en el centro: se mueve el mapa, no el pin.
                Text("📍").font(.system(size: 44)).offset(y: -22).allowsHitTesting(false)
            }

            VStack(spacing: 10) {
                barraSuperior
                if modelo.cargando { ProgressView().padding(6).background(.regularMaterial, in: Capsule()) }
                Spacer()
                if eligiendo { tarjetaElegir } else { inferior }
            }
            .padding(12)
        }
        .toolbar(.hidden, for: .navigationBar)
        .task {
            await modelo.cargarLocal()
            await modelo.refrescar()
        }
        .task {
            // "Tiempo real": se refresca cada minuto mientras el mapa está visible.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                await modelo.refrescar()
            }
        }
        .sheet(isPresented: $hojaVisible) {
            if let reporte {
                HojaReporte(modelo: reporte, fotos: c.fotos) {
                    hojaVisible = false
                    eligiendo = true
                }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
        }
        .sheet(isPresented: $invitacionVisible) {
            invitacion.presentationDetents([.medium]).presentationDragIndicator(.visible)
        }
        .onChange(of: reporte?.resultado) { _, resultado in
            guard let resultado else { return }
            hojaVisible = false
            reporte = nil
            modelo.avisar(resultado == .enviado ? "¡Reporte enviado! Sus vecinos podrán confirmarlo."
                                                : "Sin conexión: su reporte se enviará solo cuando vuelva la red.")
            Task { await modelo.refrescar() }
        }
    }

    private var barraSuperior: some View {
        HStack(spacing: 10) {
            BotonRedondo(sistema: "line.3.horizontal", etiqueta: "Menú", accion: alAbrirMenu)
                .accessibilityIdentifier("menu")
            VStack(alignment: .leading, spacing: 2) {
                Text("SERVI-RED").font(.headline).foregroundStyle(Color.accentColor)
                Text(estadoCercanos).font(.caption)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .background(.regularMaterial, in: Capsule())
            .shadow(radius: 3)
            if modelo.anonimo {
                Button("Ingresar") { alIngresar(false) }
                    .font(.headline).padding(.horizontal, 16).frame(height: 48)
                    .background(.regularMaterial, in: Capsule()).shadow(radius: 3)
                    .accessibilityIdentifier("ingresar")
            } else {
                BotonRedondo(sistema: "location.fill", etiqueta: "Centrar en mi ubicación") { Task { await modelo.volverACentrar() } }
            }
        }
    }

    /// Sin cuenta, «Reportar» invita a crearla (o a la denuncia anónima, que no la necesita).
    private var invitacion: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Para reportar un corte, cree su cuenta").font(.title3.bold())
            Text("Toma menos de un minuto. Con ella sigue la atención de su reporte, confirma los cortes de sus vecinos y avisa cuando vuelva el servicio.")
            Button { invitacionVisible = false; alIngresar(true) } label: {
                Text("Crear mi cuenta").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large).accessibilityIdentifier("crear-cuenta")
            Button { invitacionVisible = false; alIngresar(false) } label: {
                Text("Ya tengo cuenta: ingresar").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered).controlSize(.large)
            Text("¿Es una conexión ilegal o un fraude?").font(.subheadline.weight(.semibold)).padding(.top, 4)
            Button("Hacer una denuncia anónima, sin cuenta") { invitacionVisible = false; alDenunciar() }
        }
        .padding(24)
    }

    private var estadoCercanos: String {
        if modelo.anonimo { return "Explore los cortes del país" }
        if modelo.sinUbicacion { return "Active la ubicación para ver su zona" }
        if modelo.ubicacion == nil { return "Buscando su ubicación…" }
        switch modelo.cercanos.count {
        case 0: return "Sin cortes reportados cerca"
        case 1: return "1 corte reportado cerca"
        default: return "\(modelo.cercanos.count) cortes reportados cerca"
        }
    }

    @ViewBuilder private var inferior: some View {
        if modelo.pendientes > 0 {
            Text(modelo.pendientes == 1 ? "1 reporte esperando conexión" : "\(modelo.pendientes) reportes esperando conexión")
                .font(.footnote.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 6)
                .background(.regularMaterial, in: Capsule())
        }
        if let texto = modelo.error ?? modelo.aviso {
            HStack {
                Text(texto).frame(maxWidth: .infinity, alignment: .leading)
                Button { modelo.cerrarAviso() } label: { Image(systemName: "xmark") }.accessibilityLabel("Cerrar aviso")
            }
            .padding(14)
            .background((modelo.error != nil ? Color.red : Color.accentColor).opacity(0.18), in: RoundedRectangle(cornerRadius: 16))
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        }
        if let cercano = modelo.cercanoSeleccionado { tarjetaCercano(cercano) }
        if let propio = modelo.propioSeleccionado { tarjetaPropia(propio) }
        if let r = modelo.preguntaVolvio { tarjetaPregunta(r) }
        Button {
            if modelo.anonimo { invitacionVisible = true; return }
            reporte = NuevoReporteModelo(repo: c.reportes, ubicacion: c.ubicacion, descartarFoto: c.fotos.descartar)
            reporte?.ubicacion = modelo.ubicacion
            hojaVisible = true
        } label: {
            Text("⚠  Reportar").font(.title3.bold()).frame(maxWidth: .infinity).frame(height: 64)
        }
        .foregroundStyle(Color(red: 0.11, green: 0.1, blue: 0.09))
        .background(ambar, in: Capsule())
        .shadow(radius: 4)
        .accessibilityIdentifier("reportar")
    }

    private var tarjetaElegir: some View {
        Tarjeta {
            Text("Mueva el mapa hasta el lugar del corte").font(.headline)
            HStack {
                Button("Cancelar") { eligiendo = false; hojaVisible = true }.buttonStyle(.bordered)
                Button("Usar este punto") {
                    if let centroMapa { reporte?.ubicacion = centroMapa }
                    eligiendo = false
                    hojaVisible = true
                }
                .buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
            }
        }
    }

    private func tarjetaCercano(_ c: ReporteCercano) -> some View {
        Tarjeta {
            Encabezado(emoji: c.tipoServicio.emoji, titulo: "\(c.tipoServicio.sinServicio) · \(Formato.distancia(c.distanciaMetros))",
                       detalle: "\(c.magnitud.etiqueta) · \(hace(c.inicioEstimado))\n\(vecinos(c.corte))") { modelo.seleccionar(nil) }
            Text("¿A usted también le afecta?").font(.subheadline.weight(.semibold))
            HStack {
                Button("Sí, también me afecta") { Task { await modelo.validar(.CONFIRMA) } }
                    .buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
                Button("Aquí no") { Task { await modelo.validar(.CONTROVIERTE) } }.buttonStyle(.bordered)
            }
            .disabled(modelo.ocupado)
        }
    }

    private func tarjetaPropia(_ r: Reporte) -> some View {
        Tarjeta {
            Encabezado(emoji: r.tipoServicio.emoji, titulo: "Su reporte · \(r.tipoServicio.sinServicio)",
                       detalle: "\(r.estado.etiqueta) · \(hace(r.inicioEstimado))\n\(vecinos(r.validacionCorte))") { modelo.seleccionar(nil) }
            HStack {
                Button("Ya volvió") { Task { await modelo.yaVolvio(r.id) } }
                    .buttonStyle(.borderedProminent).frame(maxWidth: .infinity).disabled(modelo.ocupado)
                Button("Ver detalle") { alVerDetalle(r.id) }.buttonStyle(.bordered)
            }
        }
    }

    private func tarjetaPregunta(_ r: Reporte) -> some View {
        Tarjeta(borde: r.tipoServicio.color) {
            Encabezado(emoji: r.tipoServicio.emoji, titulo: r.tipoServicio.preguntaVolvio,
                       detalle: "\(r.tipoServicio.sinServicio) desde \(hace(r.inicioEstimado))", alCerrar: nil)
            HStack {
                Button("Sí, ya volvió") { Task { await modelo.yaVolvio(r.id) } }
                    .buttonStyle(.borderedProminent).frame(maxWidth: .infinity).disabled(modelo.ocupado)
                    .accessibilityIdentifier("ya-volvio")
                Button("Todavía no") { modelo.todaviaNo(r.id) }
            }
        }
    }

    private func hace(_ inicio: Date) -> String {
        "hace " + Formato.duracion(max(0, Int(Date().timeIntervalSince(inicio) / 60)))
    }

    private func vecinos(_ c: Conteo?) -> String {
        let n = c?.confirmaciones ?? 0
        let texto = n == 0 ? "Nadie más lo ha confirmado" : n == 1 ? "1 vecino lo confirmó" : "\(n) vecinos lo confirmaron"
        return texto + (c?.validado == true ? " · validado" : "")
    }
}

private struct BotonRedondo: View {
    let sistema: String
    let etiqueta: String
    let accion: () -> Void

    var body: some View {
        Button(action: accion) {
            Image(systemName: sistema).font(.title3).frame(width: 48, height: 48)
        }
        .foregroundStyle(.primary)
        .background(.regularMaterial, in: Circle())
        .shadow(radius: 3)
        .accessibilityLabel(etiqueta)
    }
}

private struct Tarjeta<Contenido: View>: View {
    var borde: Color?
    @ViewBuilder let contenido: Contenido

    init(borde: Color? = nil, @ViewBuilder contenido: () -> Contenido) {
        self.borde = borde
        self.contenido = contenido()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { contenido }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))
            .overlay { if let borde { RoundedRectangle(cornerRadius: 24).stroke(borde, lineWidth: 2) } }
            .shadow(radius: 4)
    }
}

private struct Encabezado: View {
    let emoji: String
    let titulo: String
    let detalle: String
    let alCerrar: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(emoji).font(.system(size: 32))
            VStack(alignment: .leading, spacing: 2) {
                Text(titulo).font(.headline)
                Text(detalle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let alCerrar {
                Button(action: alCerrar) { Image(systemName: "xmark") }.accessibilityLabel("Cerrar")
            }
        }
    }
}

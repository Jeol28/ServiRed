import SwiftUI

/// Cortes reportados por otros a menos de 1 km: confirmar ayuda a priorizarlos (RF-4).
struct CercanosVista: View {
    @State var modelo: CercanosModelo

    var body: some View {
        List {
            Text("Si a usted también le falta el servicio, confírmelo. Con 3 confirmaciones el corte queda validado por la comunidad. Su ubicación solo se usa para comprobar la distancia y no se guarda.")
                .font(.callout)
            if modelo.cargando { ProgressView() }
            Aviso(texto: modelo.aviso, error: false)
            Aviso(texto: modelo.error)
            if let u = modelo.ubicacion {
                MapaOSM(marcas: modelo.reportes.map { MarcaMapa(id: $0.id, ubicacion: $0.ubicacion, colorHex: $0.tipoServicio.hex) },
                        seleccion: u, zoom: 14.5)
                    .frame(height: 240).listRowInsets(EdgeInsets())
                if !modelo.cargando && modelo.reportes.isEmpty {
                    Text("No hay cortes activos reportados a menos de 1 km.")
                }
            }
            ForEach(modelo.reportes) { c in
                FilaCercano(c: c, ocupado: modelo.votando == c.id) { objeto, sentido in
                    Task { await modelo.validar(c.id, objeto: objeto, sentido: sentido) }
                }
            }
        }
        .navigationTitle("Cerca de usted")
        .toolbar { Button { Task { await modelo.buscar() } } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("Buscar de nuevo") }
        .refreshable { await modelo.buscar() }
        .task { await modelo.buscar() }
    }
}

private struct FilaCercano: View {
    let c: ReporteCercano
    let ocupado: Bool
    let alValidar: (ObjetoValidacion, Sentido) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(c.tipoServicio.etiqueta).font(.headline).foregroundStyle(c.tipoServicio.color)
                Etiqueta(texto: c.estado.etiqueta, color: c.estado.color)
                Text(Formato.distancia(c.distanciaMetros)).font(.caption)
            }
            Text("\(c.magnitud.etiqueta) · desde \(Formato.fechaHora(c.inicioEstimado))").font(.caption)
            Text("Corte: \(resumen(c.corte))").font(.caption)
            HStack {
                Button("También me afecta") { alValidar(.CORTE, .CONFIRMA) }.buttonStyle(.borderedProminent)
                Button("Aquí no hay corte") { alValidar(.CORTE, .CONTROVIERTE) }.buttonStyle(.bordered)
            }
            .disabled(ocupado)
            if c.estado == .RESTABLECIDO {
                Text("Regreso del servicio: \(resumen(c.restablecimiento))").font(.caption)
                HStack {
                    Button("Ya volvió") { alValidar(.RESTABLECIMIENTO, .CONFIRMA) }.buttonStyle(.borderedProminent)
                    Button("Aún no hay") { alValidar(.RESTABLECIMIENTO, .CONTROVIERTE) }.buttonStyle(.bordered)
                }
                .disabled(ocupado)
            }
        }
        .buttonStyle(.borderless)
    }

    private func resumen(_ c: Conteo?) -> String {
        guard let c else { return "sin validaciones" }
        return c.validado ? "validado (\(c.confirmaciones) confirman)" : "\(c.confirmaciones) confirman, \(c.controversias) no"
    }
}

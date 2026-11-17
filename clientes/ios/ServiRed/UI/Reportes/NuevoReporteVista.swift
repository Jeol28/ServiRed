import SwiftUI

struct NuevoReporteVista: View {
    @State var modelo: NuevoReporteModelo
    let fotos: PreparadorFotos
    @Environment(\.dismiss) private var cerrar

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                SelectorOpciones(titulo: "¿Qué servicio se fue?", opciones: TipoServicio.allCases, elegida: $modelo.tipo) { $0.etiqueta }
                SelectorOpciones(titulo: "¿A cuántos afecta?", opciones: Magnitud.allCases, elegida: $modelo.magnitud) { $0.etiqueta }
                SelectorHoraPasada(titulo: "¿Desde cuándo?", minutos: $modelo.minutosAtras, maximo: 30 * 24 * 60)
                VStack(alignment: .leading, spacing: 8) {
                    Text("¿Dónde?").font(.subheadline.weight(.semibold))
                    HStack {
                        Button(modelo.buscandoUbicacion ? "Buscando…" : "Usar mi ubicación") { Task { await modelo.usarMiUbicacion() } }
                            .buttonStyle(.bordered).disabled(modelo.buscandoUbicacion)
                        Text("o toque el mapa").font(.caption)
                    }
                    MapaOSM(seleccion: modelo.ubicacion) { modelo.ubicacion = $0 }
                        .frame(height: 260).clipShape(RoundedRectangle(cornerRadius: 12))
                }
                VStack(alignment: .leading) {
                    TextField("Descripción (opcional)", text: $modelo.descripcion, axis: .vertical)
                        .lineLimit(3...6).textFieldStyle(.roundedBorder)
                        .onChange(of: modelo.descripcion) { _, v in
                            if v.count > Reglas.maxDescripcionReporte { modelo.descripcion = String(v.prefix(Reglas.maxDescripcionReporte)) }
                        }
                    Text("\(modelo.descripcion.count)/\(Reglas.maxDescripcionReporte)").font(.caption).foregroundStyle(.secondary)
                }
                SelectorFotos(fotos: modelo.fotos, maximo: Reglas.maxFotos, preparador: fotos,
                              alAgregar: modelo.agregarFoto, alQuitar: modelo.quitarFoto, habilitado: !modelo.enviando)
                Aviso(texto: modelo.error)
                Button {
                    Task { await modelo.enviar() }
                } label: {
                    Text(modelo.enviando ? "Enviando…" : "Enviar reporte").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).controlSize(.large).disabled(modelo.enviando)
            }
            .padding()
        }
        .navigationTitle("Reportar un corte")
        .navigationBarTitleDisplayMode(.inline)
        .task { await modelo.usarMiUbicacion() }
        .alert(modelo.resultado == .enviado ? "Reporte recibido" : "Reporte guardado",
               isPresented: Binding(get: { modelo.resultado != nil }, set: { _ in })) {
            Button("Entendido") { cerrar() }
        } message: {
            Text(modelo.resultado == .enviado
                 ? "Gracias. Puede seguir su atención en «Mis reportes»."
                 : "No hay conexión ahora. El reporte quedó en el teléfono y se enviará solo cuando vuelva la red.")
        }
    }
}

import SwiftUI

/**
 Denuncia anónima (RF-5, RNF-SEG4). No pide permiso de ubicación al abrir:
 el ciudadano decide si usa la del teléfono o marca el punto en el mapa.
 */
struct DenunciaVista: View {
    @State var modelo: DenunciaModelo
    let fotos: PreparadorFotos

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Aviso(texto: "Para conexiones ilegales, fraudes o manipulación de medidores. No se envía su cuenta ni ningún dato del teléfono; la foto viaja sin metadatos (GPS, modelo, fecha). No escriba datos personales.", error: false)
                if modelo.enviada {
                    Text("Denuncia recibida").font(.title2.bold())
                    Text("Un moderador la revisará. No se le asigna un número de seguimiento para no vincularla con usted.")
                    Button("Hacer otra denuncia") { modelo.nueva() }.buttonStyle(.borderedProminent)
                } else {
                    formulario
                }
            }
            .padding()
        }
        .navigationTitle("Denuncia anónima")
    }

    @ViewBuilder private var formulario: some View {
        SelectorOpciones(titulo: "Servicio", opciones: TipoServicio.allCases, elegida: $modelo.tipo) { $0.etiqueta }
        VStack(alignment: .leading, spacing: 8) {
            Text("Toque el mapa donde ocurre").font(.subheadline.weight(.semibold))
            Button("Usar mi ubicación") { Task { await modelo.usarMiUbicacion() } }.buttonStyle(.bordered)
            MapaOSM(seleccion: modelo.ubicacion, alElegir: { modelo.ubicacion = $0 })
                .frame(height: 260).clipShape(RoundedRectangle(cornerRadius: 12))
        }
        VStack(alignment: .leading) {
            TextField("¿Qué ocurre?", text: $modelo.descripcion, axis: .vertical).lineLimit(4...8).textFieldStyle(.roundedBorder)
            Text("Entre \(Reglas.denunciaDescripcion.lowerBound) y \(Reglas.denunciaDescripcion.upperBound) caracteres")
                .font(.caption).foregroundStyle(.secondary)
        }
        SelectorFotos(fotos: modelo.foto.map { [$0] } ?? [], maximo: 1, preparador: fotos,
                      alAgregar: { modelo.ponerFoto($0) }, alQuitar: { _ in modelo.ponerFoto(nil) }, habilitado: !modelo.enviando)
        Aviso(texto: modelo.error)
        Button {
            Task { await modelo.enviar() }
        } label: {
            Text(modelo.enviando ? "Enviando…" : "Enviar denuncia").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent).controlSize(.large).disabled(modelo.enviando)
    }
}

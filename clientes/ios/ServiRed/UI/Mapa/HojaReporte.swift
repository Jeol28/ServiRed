import SwiftUI

private let atajosHora = [(0, "Ahora"), (30, "Hace 30 min"), (60, "Hace 1 h"), (180, "Hace 3 h"), (720, "Hace 12 h"), (1440, "Ayer")]

/// Reporte rápido (RF-1, RF-2): qué se fue, a cuántos afecta y enviar. La hora es
/// "ahora" y la ubicación la del teléfono, salvo que se cambien; la foto y la
/// descripción son opcionales.
struct HojaReporte: View {
    @Bindable var modelo: NuevoReporteModelo
    let fotos: PreparadorFotos
    let alElegirEnMapa: () -> Void
    @State private var detalles = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("¿Qué se fue?").font(.title2.bold())
                Flujo(espacio: 10) {
                    ForEach(TipoServicio.allCases) { t in BotonServicio(tipo: t, elegido: modelo.tipo == t) { modelo.tipo = t } }
                }

                Text("¿A cuántos afecta?").font(.headline)
                Flujo {
                    ForEach(Magnitud.allCases) { m in
                        Button(m.corta) { modelo.magnitud = m }
                            .buttonStyle(.bordered).tint(modelo.magnitud == m ? .accentColor : .secondary)
                            .accessibilityAddTraits(modelo.magnitud == m ? .isSelected : [])
                    }
                }

                Text("¿Desde cuándo?").font(.headline)
                Flujo {
                    ForEach(atajosHora, id: \.0) { minutos, texto in
                        Button(texto) { modelo.minutosAtras = minutos }
                            .buttonStyle(.bordered).tint(modelo.minutosAtras == minutos ? .accentColor : .secondary)
                    }
                }

                HStack {
                    Text(modelo.buscandoUbicacion ? "📍 Buscando su ubicación…"
                         : modelo.ubicacion == nil ? "📍 Falta la ubicación" : "📍 Donde está usted ahora")
                    Spacer()
                    Button(modelo.ubicacion == nil ? "Elegir en el mapa" : "Cambiar", action: alElegirEnMapa)
                }

                if detalles {
                    TextField("Descripción (opcional)", text: $modelo.descripcion, axis: .vertical)
                        .lineLimit(2...5).textFieldStyle(.roundedBorder)
                    SelectorFotos(fotos: modelo.fotos, maximo: Reglas.maxFotos, preparador: fotos,
                                  alAgregar: modelo.agregarFoto, alQuitar: modelo.quitarFoto, habilitado: !modelo.enviando)
                } else {
                    Button("+ Agregar foto o descripción (opcional)") { detalles = true }
                }

                Aviso(texto: modelo.error)
                Button {
                    Task { await modelo.enviar() }
                } label: {
                    Text(modelo.enviando ? "Enviando…" : "Enviar reporte").font(.headline).frame(maxWidth: .infinity).frame(height: 52)
                }
                .buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                .disabled(modelo.enviando || modelo.tipo == nil || modelo.magnitud == nil)
                .accessibilityIdentifier("enviar-reporte")
            }
            .padding(20)
        }
        .task { if modelo.ubicacion == nil { await modelo.usarMiUbicacion() } }
    }
}

private struct BotonServicio: View {
    let tipo: TipoServicio
    let elegido: Bool
    let accion: () -> Void

    var body: some View {
        Button(action: accion) {
            VStack(spacing: 4) {
                Text(tipo.emoji).font(.system(size: 30))
                Text(tipo.corto).font(.footnote.weight(.semibold)).multilineTextAlignment(.center)
            }
            .frame(width: 100, height: 92)
            .background(elegido ? tipo.color.opacity(0.18) : Color.secondary.opacity(0.15), in: RoundedRectangle(cornerRadius: 20))
            .overlay { if elegido { RoundedRectangle(cornerRadius: 20).stroke(tipo.color, lineWidth: 3) } }
        }
        .foregroundStyle(.primary)
        .accessibilityAddTraits(elegido ? .isSelected : [])
        .accessibilityIdentifier("servicio-\(tipo.rawValue)")
    }
}

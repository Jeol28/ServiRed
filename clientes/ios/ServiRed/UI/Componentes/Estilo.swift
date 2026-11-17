import SwiftUI

/// Colores por servicio y estado, los mismos de la app Android y los portales.
extension TipoServicio {
    var color: Color {
        switch self {
        case .ENERGIA: Color(red: 0.85, green: 0.47, blue: 0.02)
        case .GAS: Color(red: 0.86, green: 0.15, blue: 0.15)
        case .ACUEDUCTO: Color(red: 0.15, green: 0.39, blue: 0.92)
        case .ALCANTARILLADO: Color(red: 0.49, green: 0.23, blue: 0.93)
        case .ASEO: Color(red: 0.02, green: 0.59, blue: 0.41)
        }
    }

    var hex: String {
        switch self {
        case .ENERGIA: "#D97706"
        case .GAS: "#DC2626"
        case .ACUEDUCTO: "#2563EB"
        case .ALCANTARILLADO: "#7C3AED"
        case .ASEO: "#059669"
        }
    }
}

extension EstadoReporte {
    var color: Color {
        switch self {
        case .RECIBIDO: .gray
        case .EN_ATENCION: .orange
        case .RESTABLECIDO: .green
        case .CERRADO: .secondary
        }
    }
}

enum Formato {
    static func fechaHora(_ d: Date?) -> String {
        guard let d else { return "—" }
        return d.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(Locale(identifier: "es_CO")))
    }

    static func duracion(_ minutos: Int?) -> String {
        guard let minutos else { return "—" }
        let d = minutos / 1440, h = (minutos % 1440) / 60, m = minutos % 60
        var partes: [String] = []
        if d > 0 { partes.append("\(d) d") }
        if h > 0 { partes.append("\(h) h") }
        if m > 0 || (d == 0 && h == 0) { partes.append("\(m) min") }
        return partes.joined(separator: " ")
    }

    static func distancia(_ metros: Int) -> String {
        metros < 1000 ? "a \(metros) m" : "a \(String(format: "%.1f", Double(metros) / 1000)) km"
    }
}

/// Aviso visible y anunciado por VoiceOver.
struct Aviso: View {
    let texto: String?
    var error = true

    var body: some View {
        if let texto {
            Text(texto)
                .font(.callout)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background((error ? Color.red : Color.accentColor).opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(error ? Color.red : Color.primary)
                .accessibilityAddTraits(.updatesFrequently)
        }
    }
}

struct Etiqueta: View {
    let texto: String
    let color: Color

    var body: some View {
        Text(texto).font(.caption.weight(.semibold)).foregroundStyle(color)
    }
}

/// Disposición en filas que se parten (chips).
struct Flujo: Layout {
    var espacio: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let ancho = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, alto: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let t = s.sizeThatFits(.unspecified)
            if x > 0, x + t.width > ancho { x = 0; y += alto + espacio; alto = 0 }
            x += t.width + espacio
            maxX = max(maxX, x - espacio)
            alto = max(alto, t.height)
        }
        return CGSize(width: proposal.width ?? maxX, height: y + alto)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, alto: CGFloat = 0
        for s in subviews {
            let t = s.sizeThatFits(.unspecified)
            if x > bounds.minX, x + t.width > bounds.maxX { x = bounds.minX; y += alto + espacio; alto = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(t))
            x += t.width + espacio
            alto = max(alto, t.height)
        }
    }
}

struct SelectorOpciones<T: Hashable & Identifiable>: View {
    let titulo: String
    let opciones: [T]
    @Binding var elegida: T?
    let etiqueta: (T) -> String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(titulo).font(.subheadline.weight(.semibold))
            Flujo {
                ForEach(opciones) { o in
                    Button(etiqueta(o)) { elegida = o }
                        .buttonStyle(.bordered)
                        .tint(elegida == o ? .accentColor : .secondary)
                        .accessibilityAddTraits(elegida == o ? .isSelected : [])
                }
            }
        }
    }
}

/// Elegir hace cuánto ocurrió algo (atajos y pasos de 15 minutos).
struct SelectorHoraPasada: View {
    let titulo: String
    @Binding var minutos: Int
    let maximo: Int

    private let atajos = [(0, "Ahora"), (15, "15 min"), (30, "30 min"), (60, "1 h"), (120, "2 h"), (240, "4 h"), (480, "8 h"), (1440, "1 día")]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(titulo).font(.subheadline.weight(.semibold))
            Flujo {
                ForEach(atajos.filter { $0.0 <= maximo }, id: \.0) { m, texto in
                    Button(texto) { minutos = m }
                        .buttonStyle(.bordered)
                        .tint(minutos == m ? .accentColor : .secondary)
                }
            }
            HStack {
                Button("15 min antes") { minutos = min(maximo, minutos + 15) }.disabled(minutos >= maximo)
                Button("15 min después") { minutos = max(0, minutos - 15) }.disabled(minutos <= 0)
            }
            .buttonStyle(.bordered)
            Text((minutos == 0 ? "Ahora" : "Hace \(Formato.duracion(minutos))") + " · "
                + Formato.fechaHora(Date().addingTimeInterval(-Double(minutos) * 60)))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

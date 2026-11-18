import SwiftUI
import UIKit

extension TipoServicio {
    /// Símbolo de cada servicio en el mapa y en el formulario de reporte.
    var emoji: String {
        switch self {
        case .ENERGIA: "⚡"
        case .ACUEDUCTO: "💧"
        case .GAS: "🔥"
        case .ALCANTARILLADO: "🌊"
        case .ASEO: "🗑️"
        }
    }

    /// Cómo lo dice el ciudadano: "se fue la luz", no "interrupción de energía".
    var sinServicio: String {
        switch self {
        case .ENERGIA: "Sin luz"
        case .ACUEDUCTO: "Sin agua"
        case .GAS: "Sin gas"
        case .ALCANTARILLADO: "Alcantarillado"
        case .ASEO: "Sin recolección de basura"
        }
    }

    /// Nombre corto para los botones del reporte.
    var corto: String {
        switch self {
        case .ENERGIA: "Luz"
        case .ACUEDUCTO: "Agua"
        case .GAS: "Gas"
        case .ALCANTARILLADO: "Alcantarillado"
        case .ASEO: "Basura"
        }
    }

    /// Pregunta para un reporte propio activo.
    var preguntaVolvio: String {
        switch self {
        case .ENERGIA: "¿Ya volvió la luz?"
        case .ACUEDUCTO: "¿Ya volvió el agua?"
        case .GAS: "¿Ya volvió el gas?"
        case .ALCANTARILLADO: "¿Ya se solucionó el alcantarillado?"
        case .ASEO: "¿Ya pasó el carro de la basura?"
        }
    }
}

extension Magnitud {
    var corta: String {
        switch self {
        case .VIVIENDA: "Mi casa"
        case .CUADRA: "Mi cuadra"
        case .BARRIO: "Mi barrio"
        case .SECTOR: "Un sector"
        }
    }
}

extension UIColor {
    /// "#RRGGBB" -> color.
    convenience init(hex: String) {
        let v = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0
        self.init(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
}

/// Ícono de un marcador: círculo del color del servicio con su símbolo; los propios llevan anillo oscuro.
func iconoMarcador(emoji: String?, colorHex: String, propio: Bool) -> UIImage {
    let lado: CGFloat = 44
    return UIGraphicsImageRenderer(size: CGSize(width: lado, height: lado)).image { _ in
        let borde: CGFloat = propio ? 4.5 : 3
        (propio ? UIColor(hex: "#0F172A") : .white).setFill()
        UIBezierPath(ovalIn: CGRect(x: 0, y: 0, width: lado, height: lado)).fill()
        UIColor(hex: colorHex).setFill()
        UIBezierPath(ovalIn: CGRect(x: borde, y: borde, width: lado - 2 * borde, height: lado - 2 * borde)).fill()
        guard let emoji else { return }
        let texto = NSAttributedString(string: emoji, attributes: [.font: UIFont.systemFont(ofSize: lado * 0.46)])
        let t = texto.size()
        texto.draw(at: CGPoint(x: (lado - t.width) / 2, y: (lado - t.height) / 2))
    }
}

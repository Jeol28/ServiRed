import Foundation

/// Fechas ISO-8601. Al servidor siempre se envían con la zona del teléfono
/// (RNF-DAT1), p. ej. "2026-11-17T08:00:00-05:00"; se aceptan "Z", offsets y
/// fracciones de segundo.
enum Fechas {
    static func texto(_ fecha: Date, zona: TimeZone = .current) -> String {
        let f = ISO8601DateFormatter()
        f.timeZone = zona
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: fecha)
    }

    static func desde(_ texto: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: texto) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: texto)
    }

    /// Para el mapeo de DTO: una fecha ilegible es una respuesta inválida.
    static func exigir(_ texto: String) throws -> Date {
        guard let d = desde(texto) else { throw ErrorApp(tipo: .servidor, mensaje: "Respuesta inesperada del servidor.") }
        return d
    }
}

import Foundation

/// Reglas que la app comprueba antes de enviar, iguales a las del servidor
/// (que es quien decide) y a las de la app Android.
enum Reglas {
    static let maxFotos = 3
    static let maxBytesFoto = 5 * 1024 * 1024
    static let confirmacionesParaValidar = 3
    static let maxDescripcionReporte = 1000
    static let maxComentarioRestablecimiento = 500
    static let denunciaDescripcion = 10...2000
    private static let toleranciaReloj: TimeInterval = 5 * 60
    private static let antiguedadMaxima: TimeInterval = 30 * 24 * 3600
    private static let latitud = -4.3...13.5
    private static let longitud = -82.0 ... -66.8

    static func enColombia(_ u: Ubicacion) -> Bool { latitud.contains(u.latitud) && longitud.contains(u.longitud) }

    static func validarReporte(_ r: NuevoReporte, ahora: Date) -> [String] {
        var errores: [String] = []
        if !enColombia(r.ubicacion) { errores.append("La ubicación debe estar en Colombia.") }
        if r.inicioEstimado > ahora.addingTimeInterval(toleranciaReloj) { errores.append("La hora de inicio no puede ser futura.") }
        if r.inicioEstimado < ahora.addingTimeInterval(-antiguedadMaxima) {
            errores.append("La hora de inicio no puede tener más de 30 días.")
        }
        if (r.descripcion?.count ?? 0) > maxDescripcionReporte {
            errores.append("La descripción admite hasta \(maxDescripcionReporte) caracteres.")
        }
        return errores
    }

    static func validarRestablecimiento(fecha: Date, inicio: Date, ahora: Date, comentario: String?) -> String? {
        if fecha <= inicio { return "El servicio volvió después de que empezó el corte: revise la hora." }
        if fecha > ahora.addingTimeInterval(toleranciaReloj) { return "La hora de restablecimiento no puede ser futura." }
        if (comentario?.count ?? 0) > maxComentarioRestablecimiento {
            return "El comentario admite hasta \(maxComentarioRestablecimiento) caracteres."
        }
        return nil
    }

    static func validarDenuncia(_ d: NuevaDenuncia) -> [String] {
        var errores: [String] = []
        if !enColombia(d.ubicacion) { errores.append("La ubicación debe estar en Colombia.") }
        let largo = d.descripcion.trimmingCharacters(in: .whitespacesAndNewlines).count
        if !denunciaDescripcion.contains(largo) {
            errores.append("La descripción debe tener entre \(denunciaDescripcion.lowerBound) y \(denunciaDescripcion.upperBound) caracteres.")
        }
        return errores
    }

    static func validarCorreo(_ correo: String) -> String? {
        let c = correo.trimmingCharacters(in: .whitespaces)
        return c.count <= 254 && c.wholeMatch(of: /[^@\s]+@[^@\s]+\.[^@\s]+/) != nil ? nil : "Escriba un correo válido."
    }

    /// Entre 10 y 64 caracteres, máximo 72 bytes (bcrypt) y distinta del correo.
    static func validarClave(_ clave: String, correo: String) -> String? {
        if clave.count < 10 || clave.count > 64 { return "La clave debe tener entre 10 y 64 caracteres." }
        if clave.utf8.count > 72 { return "La clave es demasiado larga." }
        if clave.lowercased() == correo.trimmingCharacters(in: .whitespaces).lowercased() {
            return "La clave no puede ser igual al correo."
        }
        return nil
    }
}

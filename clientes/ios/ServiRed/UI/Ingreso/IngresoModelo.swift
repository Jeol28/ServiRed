import Foundation
import Observation

/// Ingreso y registro del ciudadano. Al abrir la sesión, la navegación cambia sola.
@MainActor
@Observable
final class IngresoModelo {
    var registro = false
    var correo = ""
    var clave = ""
    var nombre = ""
    var aceptaTratamiento = false
    private(set) var cargando = false
    var error: String?

    private let sesion: RepositorioSesion

    init(sesion: RepositorioSesion, registro: Bool = false) {
        self.sesion = sesion
        self.registro = registro
    }

    func alternarModo() {
        registro.toggle()
        error = nil
        clave = ""
    }

    func enviar() async {
        let problema = Reglas.validarCorreo(correo) ?? (registro
            ? Reglas.validarClave(clave, correo: correo)
                ?? (aceptaTratamiento ? nil : "Debe autorizar el tratamiento de sus datos para crear la cuenta.")
            : (clave.isEmpty ? "Escriba su clave." : nil))
        if let problema {
            error = problema
            return
        }
        cargando = true
        error = nil
        defer { cargando = false }
        do {
            if registro {
                try await sesion.registrar(correo: correo, clave: clave, nombre: nombre)
            } else {
                try await sesion.ingresar(correo: correo, clave: clave)
            }
            clave = ""
        } catch {
            self.error = ErrorApp.de(error).mensaje
        }
    }
}

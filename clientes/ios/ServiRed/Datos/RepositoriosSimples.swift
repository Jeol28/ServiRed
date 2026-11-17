import Foundation

/**
 Denuncia anónima (RF-5, RNF-SEG4): usa el cliente sin gestor de tokens, que
 no agrega JWT ni correlationId. La foto va con un nombre neutro, ya sin
 metadatos, y se borra al enviarla. No se guarda ningún rastro local.
 */
struct RepositorioDenunciasImpl: RepositorioDenuncias {
    let publica: ClienteAPI
    let fotos: CarpetaFotos

    func enviar(_ denuncia: NuevaDenuncia, foto: String?) async throws {
        if let e = Reglas.validarDenuncia(denuncia).first { throw ErrorApp.validacion(e) }
        defer { foto.map(fotos.borrar) }
        let dto = denuncia.dto
        if let foto, let datos = fotos.datos(foto) {
            var m = Multipart()
            m.parte(nombre: "denuncia", tipo: "application/json", contenido: try JSONEncoder().encode(dto))
            m.parte(nombre: "foto", archivo: "foto.jpg", tipo: "image/jpeg", contenido: datos)
            _ = try await publica.ejecutar(Peticion(metodo: "POST", ruta: "api/v1/denuncias", cuerpo: m.cuerpo(), tipoContenido: m.tipoContenido))
        } else {
            _ = try await publica.ejecutar(.json("POST", "api/v1/denuncias", dto))
        }
    }
}

struct RepositorioCuentaImpl: RepositorioCuenta {
    let api: ClienteAPI
    /// Termina la sesión local (y borra los datos del teléfono).
    let cerrarSesion: @Sendable () async -> Void

    func yo() async throws -> Cuenta {
        try await (api.json(Peticion(metodo: "GET", ruta: "api/v1/cuentas/yo")) as CuentaDTO).dominio
    }

    func cambiarNombre(_ nombre: String) async throws -> Cuenta {
        let n = nombre.trimmingCharacters(in: .whitespaces)
        if n.count > 120 { throw ErrorApp.validacion("El nombre admite hasta 120 caracteres.") }
        return try await (api.json(.json("PATCH", "api/v1/cuentas/yo", CambiosCuentaPropiaDTO(nombre: n))) as CuentaDTO).dominio
    }

    /// Cambiar la clave cierra todas las sesiones en el servidor: se termina también la local.
    func cambiarClave(actual: String, nueva: String) async throws {
        let correo = (try? await yo())?.correo ?? ""
        if let e = Reglas.validarClave(nueva, correo: correo) { throw ErrorApp.validacion(e) }
        _ = try await api.ejecutar(.json("PATCH", "api/v1/cuentas/yo", CambiosCuentaPropiaDTO(claveActual: actual, claveNueva: nueva)))
        await cerrarSesion()
    }

    /// Derecho de supresión: el servidor anonimiza la cuenta; aquí se borra todo lo local.
    func eliminar() async throws {
        _ = try await api.ejecutar(Peticion(metodo: "DELETE", ruta: "api/v1/cuentas/yo"))
        await cerrarSesion()
    }
}

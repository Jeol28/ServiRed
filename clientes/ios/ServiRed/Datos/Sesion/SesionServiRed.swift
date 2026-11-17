import Foundation
import Observation

/**
 Sesión del ciudadano. El resumen de la cuenta se guarda junto al refresco
 para abrir la app sin red (ver reportes guardados y dejar pendientes); el
 token de acceso se obtiene en la primera llamada.
 */
@MainActor
@Observable
final class SesionServiRed: RepositorioSesion {
    private(set) var estado: EstadoSesion = .desconocida

    @ObservationIgnored let gestor: GestorTokens
    @ObservationIgnored private let publica: ClienteAPI
    @ObservationIgnored private let cofre: CofreTokens
    /// Borra los datos personales locales (reportes guardados, pendientes y fotos).
    @ObservationIgnored private let alCerrar: @Sendable () async -> Void

    init(base: URL, transporte: any Transporte, cofre: CofreTokens, alCerrar: @escaping @Sendable () async -> Void) {
        let referencia = Referencia()
        self.cofre = cofre
        self.alCerrar = alCerrar
        publica = ClienteAPI(base: base, transporte: transporte, gestor: nil)
        let refrescador = Refrescador(base: base, transporte: transporte)
        gestor = GestorTokens(
            cofre: cofre,
            refrescar: { await refrescador.refrescar($0) },
            alGuardar: { s in Task { @MainActor in referencia.sesion?.recordar(s.cuenta) } },
            alTerminar: { Task { @MainActor in referencia.sesion?.estado = .anonima } }
        )
        referencia.sesion = self
    }

    func restaurar() async {
        let cuenta = cofre.leerCuenta().flatMap { try? JSONDecoder().decode(ResumenCuentaDTO.self, from: $0) }
        if await gestor.hayRefresco(), let cuenta {
            estado = Self.estado(de: cuenta)
        } else {
            estado = .anonima
        }
    }

    func ingresar(correo: String, clave: String) async throws {
        let sesion: SesionDTO
        do {
            sesion = try await publica.json(.json("POST", "api/v1/auth/login",
                                                   CredencialesDTO(correo: correo.trimmingCharacters(in: .whitespaces), clave: clave)))
        } catch let e as ErrorApp where e.tipo == .sesion {
            throw ErrorApp(tipo: .sesion, mensaje: "Correo o clave incorrectos.")
        }
        guard sesion.cuenta.rol == "CIUDADANO" else {
            throw ErrorApp(tipo: .prohibido, mensaje: "Esta app es para ciudadanos. El personal usa el portal de operación.")
        }
        await gestor.guardar(sesion)
        recordar(sesion.cuenta)
    }

    func registrar(correo: String, clave: String, nombre: String?) async throws {
        let n = nombre?.trimmingCharacters(in: .whitespaces)
        let nuevo = NuevoCiudadanoDTO(correo: correo.trimmingCharacters(in: .whitespaces), clave: clave,
                                      nombre: n?.isEmpty == false ? n : nil, aceptaTratamientoDatos: true)
        _ = try await publica.ejecutar(.json("POST", "api/v1/auth/registro", nuevo))
        try await ingresar(correo: correo, clave: clave)
    }

    func cerrar() async {
        let refresco = await gestor.limpiar()
        estado = .anonima
        await alCerrar()
        // Revocar en el servidor es de mejor esfuerzo: localmente la sesión ya terminó.
        if let refresco {
            _ = try? await publica.ejecutar(.json("POST", "api/v1/auth/cerrar-sesion", SolicitudRefrescoDTO(tokenRefresco: refresco)))
        }
    }

    private func recordar(_ cuenta: ResumenCuentaDTO) {
        if let datos = try? JSONEncoder().encode(cuenta) { cofre.guardarCuenta(datos) }
        estado = Self.estado(de: cuenta)
    }

    private static func estado(de c: ResumenCuentaDTO) -> EstadoSesion {
        .autenticada(idCuenta: c.idCuenta, rol: c.rol, nombre: c.nombre)
    }

    private final class Referencia: @unchecked Sendable {
        weak var sesion: SesionServiRed?
    }
}

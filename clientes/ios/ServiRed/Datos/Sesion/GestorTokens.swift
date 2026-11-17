import Foundation

/// Almacén seguro del token de refresco y del resumen de la cuenta.
protocol CofreTokens: Sendable {
    func leerRefresco() -> String?
    func guardarRefresco(_ refresco: String)
    func borrarRefresco()
    func leerCuenta() -> Data?
    func guardarCuenta(_ cuenta: Data)
    func borrarTodo()
}

/// Resultado de intentar renovar la sesión con el token de refresco.
enum Renovacion: Sendable {
    case lista(SesionDTO)
    /// El servidor rechazó el refresco (vencido, revocado o reutilizado).
    case rechazada
    /// La petición no salió del teléfono: el refresco sigue siendo válido.
    case sinEnviar
    /// Falló después de enviarse: no se sabe si el servidor lo consumió.
    case incierta
}

/**
 Tokens de la sesión (ADR-18):
 - El JWT de acceso (15 min) vive solo en memoria.
 - El refresco (14 días, un solo uso) vive en el Keychain. Se retira ANTES de
   usarlo: Identidad trata su reutilización como robo y revoca todas las
   sesiones, así que nunca se envía dos veces. Solo vuelve al Keychain si la
   petición no llegó a salir.
 - Renovaciones simultáneas comparten una sola petición.
 */
actor GestorTokens {
    private(set) var acceso: String?
    private let cofre: CofreTokens
    private let refrescar: @Sendable (String) async -> Renovacion
    private let alGuardar: @Sendable (SesionDTO) -> Void
    private let alTerminar: @Sendable () -> Void
    private var enCurso: Task<String?, Never>?

    init(
        cofre: CofreTokens,
        refrescar: @escaping @Sendable (String) async -> Renovacion,
        alGuardar: @escaping @Sendable (SesionDTO) -> Void = { _ in },
        alTerminar: @escaping @Sendable () -> Void = {}
    ) {
        self.cofre = cofre
        self.refrescar = refrescar
        self.alGuardar = alGuardar
        self.alTerminar = alTerminar
    }

    func hayRefresco() -> Bool { cofre.leerRefresco() != nil }

    func guardar(_ sesion: SesionDTO) {
        acceso = sesion.tokenAcceso
        cofre.guardarRefresco(sesion.tokenRefresco)
        alGuardar(sesion)
    }

    /// Un token de acceso válido distinto de `fallido` (renovando si hace falta), o nil si la sesión terminó.
    func renovar(fallido: String?) async -> String? {
        if let tarea = enCurso { return await tarea.value }
        if let a = acceso, a != fallido { return a }
        guard let refresco = cofre.leerRefresco() else { return nil }
        cofre.borrarRefresco()
        let tarea = Task { await completar(refresco) }
        enCurso = tarea
        let token = await tarea.value
        enCurso = nil
        return token
    }

    private func completar(_ refresco: String) async -> String? {
        switch await refrescar(refresco) {
        case .lista(let sesion):
            guardar(sesion)
            return sesion.tokenAcceso
        case .sinEnviar:
            cofre.guardarRefresco(refresco)
            return nil
        case .rechazada, .incierta:
            acceso = nil
            cofre.borrarTodo()
            alTerminar()
            return nil
        }
    }

    /// Termina la sesión local y devuelve el refresco (para revocarlo en el servidor).
    func limpiar() -> String? {
        let refresco = cofre.leerRefresco()
        acceso = nil
        cofre.borrarTodo()
        return refresco
    }
}

/// Llama a /auth/refrescar y clasifica el resultado para el gestor.
struct Refrescador: Sendable {
    let base: URL
    let transporte: any Transporte

    func refrescar(_ refresco: String) async -> Renovacion {
        var req = URLRequest(url: base.appending(path: "api/v1/auth/refrescar"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONEncoder().encode(SolicitudRefrescoDTO(tokenRefresco: refresco))
        do {
            let (datos, r) = try await transporte.enviar(req)
            if (200..<300).contains(r.statusCode), let s = try? JSONDecoder().decode(SesionDTO.self, from: datos) { return .lista(s) }
            return (400..<500).contains(r.statusCode) ? .rechazada : .incierta
        } catch let e as URLError where Self.sinEnviar.contains(e.code) {
            return .sinEnviar
        } catch {
            return .incierta
        }
    }

    /// Errores en los que la petición no llegó a salir del teléfono.
    static let sinEnviar: Set<URLError.Code> = [
        .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed,
        .internationalRoamingOff, .dataNotAllowed,
    ]
}

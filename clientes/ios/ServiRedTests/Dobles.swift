import Foundation
@testable import ServiRed

/// Cofre en memoria (sin Keychain).
final class CofreMemoria: CofreTokens, @unchecked Sendable {
    private let candado = NSLock()
    private var refresco: String?
    private var cuenta: Data?

    init(refresco: String? = nil) { self.refresco = refresco }

    func leerRefresco() -> String? { candado.withLock { refresco } }
    func guardarRefresco(_ r: String) { candado.withLock { refresco = r } }
    func borrarRefresco() { candado.withLock { refresco = nil } }
    func leerCuenta() -> Data? { candado.withLock { cuenta } }
    func guardarCuenta(_ c: Data) { candado.withLock { cuenta = c } }
    func borrarTodo() { candado.withLock { refresco = nil; cuenta = nil } }
}

/// Transporte en memoria: responde en orden lo encolado y registra cada petición.
actor TransporteFalso: Transporte {
    enum Respuesta {
        case http(Int, String)
        case falla(URLError.Code)
    }

    private var cola: [Respuesta] = []
    private(set) var peticiones: [URLRequest] = []

    func encolar(_ r: Respuesta...) { cola += r }

    func enviar(_ peticion: URLRequest) async throws -> (Data, HTTPURLResponse) {
        peticiones.append(peticion)
        guard !cola.isEmpty else { throw URLError(.cannotConnectToHost) }
        switch cola.removeFirst() {
        case .falla(let codigo):
            throw URLError(codigo)
        case .http(let codigo, let cuerpo):
            let r = HTTPURLResponse(url: peticion.url!, statusCode: codigo, httpVersion: nil,
                                    headerFields: ["Content-Type": "application/json"])!
            return (Data(cuerpo.utf8), r)
        }
    }

    func limpiarRegistro() { peticiones.removeAll() }
}

struct ProgramadorFalso: ProgramadorEnvio {
    let contador = Contador()
    func programar() { contador.sumar() }
}

final class Contador: @unchecked Sendable {
    private let candado = NSLock()
    private var n = 0
    var valor: Int { candado.withLock { n } }
    func sumar() { candado.withLock { n += 1 } }
}

struct UbicacionFija: ProveedorUbicacion {
    var u: Ubicacion? = Ubicacion(latitud: 4.6287, longitud: -74.0648)
    func actual() async -> Ubicacion? { u }
}

let base = URL(string: "http://servired.prueba/")!

func sesionJSON(_ acceso: String, _ refresco: String) -> String {
    """
    {"tokenAcceso":"\(acceso)","tipoToken":"Bearer","expiraEn":900,"tokenRefresco":"\(refresco)",
     "cuenta":{"idCuenta":"c-1","rol":"CIUDADANO","nombre":"Ana"}}
    """
}

func sesionDTO(_ acceso: String, _ refresco: String) -> SesionDTO {
    try! JSONDecoder().decode(SesionDTO.self, from: Data(sesionJSON(acceso, refresco).utf8))
}

func reporteJSON(_ id: String = "r-1", estado: String = "RECIBIDO") -> String {
    """
    {"idReporte":"\(id)","tipoServicio":"ENERGIA","ubicacion":{"latitud":4.6287,"longitud":-74.0648},
     "codigoMunicipio":"11001","nombreMunicipio":"BOGOTÁ, D.C.","inicioEstimado":"2026-11-17T07:00:00-05:00",
     "magnitud":"CUADRA","estado":"\(estado)","fechaRegistro":"2026-11-17T12:05:00.123456Z",
     "validacion":{"corte":{"confirmaciones":1,"controversias":0,"confirmacionesNetas":1}},
     "evidencias":[],"historia":[{"estado":"RECIBIDO","fecha":"2026-11-17T12:05:00Z","detalle":"Reporte recibido"}]}
    """
}

extension URLRequest {
    func cabecera(_ nombre: String) -> String? { value(forHTTPHeaderField: nombre) }
    var cuerpoTexto: String { httpBody.map { String(decoding: $0, as: UTF8.self) } ?? "" }
}

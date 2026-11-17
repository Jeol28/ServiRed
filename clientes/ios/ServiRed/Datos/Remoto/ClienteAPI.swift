import Foundation

let cabeceraCorrelacion = "X-Correlation-Id"

/// Envía una petición HTTP. Inyectable: las pruebas usan un transporte en memoria.
protocol Transporte: Sendable {
    func enviar(_ peticion: URLRequest) async throws -> (Data, HTTPURLResponse)
}

/// URLSession efímera: sin cookies, sin caché en disco y con un agente genérico
/// (ningún dato del modelo del teléfono en las peticiones).
struct TransporteURLSession: Transporte {
    let sesion: URLSession

    init() {
        let c = URLSessionConfiguration.ephemeral
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.urlCache = nil
        c.timeoutIntervalForRequest = 30
        c.httpAdditionalHeaders = ["User-Agent": "ServiRed-iOS"]
        sesion = URLSession(configuration: c)
    }

    func enviar(_ peticion: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (datos, respuesta) = try await sesion.data(for: peticion)
        guard let http = respuesta as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (datos, http)
    }
}

struct Peticion: Sendable {
    var metodo: String
    var ruta: String
    var consulta: [URLQueryItem] = []
    var cuerpo: Data? = nil
    var tipoContenido: String? = nil
    /// Fijo para operaciones idempotentes (crear reporte, subir foto); si no, uno nuevo.
    var correlacion: String? = nil

    static func json<T: Encodable>(_ metodo: String, _ ruta: String, _ cuerpo: T, correlacion: String? = nil) -> Peticion {
        Peticion(metodo: metodo, ruta: ruta, cuerpo: try? JSONEncoder().encode(cuerpo),
                 tipoContenido: "application/json", correlacion: correlacion)
    }
}

/**
 Cliente del gateway.
 - Con `gestor` (operaciones del ciudadano): agrega X-Correlation-Id y el JWT;
   ante un 401 renueva una sola vez y repite con el mismo correlationId.
 - Sin `gestor` (autenticación y denuncias anónimas): no agrega NINGUNA
   cabecera de identificación (RNF-SEG4).
 */
struct ClienteAPI: Sendable {
    let base: URL
    let transporte: any Transporte
    let gestor: GestorTokens?

    func ejecutar(_ p: Peticion) async throws -> (Data, HTTPURLResponse) {
        var req = construir(p)
        guard let gestor else { return try validar(try await enviar(req)) }
        req.setValue(p.correlacion ?? UUID().uuidString.lowercased(), forHTTPHeaderField: cabeceraCorrelacion)
        var token = await gestor.acceso
        if token == nil, await gestor.hayRefresco() { token = await gestor.renovar(fallido: nil) }
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (datos, respuesta) = try await enviar(req)
        if respuesta.statusCode == 401, let token, let nuevo = await gestor.renovar(fallido: token) {
            req.setValue("Bearer \(nuevo)", forHTTPHeaderField: "Authorization")
            return try validar(try await enviar(req))
        }
        return try validar((datos, respuesta))
    }

    func json<T: Decodable>(_ p: Peticion, como: T.Type = T.self) async throws -> T {
        let (datos, _) = try await ejecutar(p)
        do {
            return try JSONDecoder().decode(T.self, from: datos)
        } catch {
            throw ErrorApp(tipo: .servidor, mensaje: "Respuesta inesperada del servidor.")
        }
    }

    private func construir(_ p: Peticion) -> URLRequest {
        var componentes = URLComponents(url: base.appending(path: p.ruta), resolvingAgainstBaseURL: false)!
        if !p.consulta.isEmpty { componentes.queryItems = p.consulta }
        var req = URLRequest(url: componentes.url!)
        req.httpMethod = p.metodo
        req.httpBody = p.cuerpo
        if let tipo = p.tipoContenido { req.setValue(tipo, forHTTPHeaderField: "Content-Type") }
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        return req
    }

    private func enviar(_ req: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            return try await transporte.enviar(req)
        } catch let e as ErrorApp {
            throw e
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw ErrorApp.sinConexion
        }
    }

    private func validar(_ r: (Data, HTTPURLResponse)) throws -> (Data, HTTPURLResponse) {
        guard (200..<300).contains(r.1.statusCode) else { throw ErrorApp.desde(status: r.1.statusCode, cuerpo: r.0) }
        return r
    }
}

extension ErrorApp {
    /// Traduce una respuesta problem+json del gateway.
    static func desde(status: Int, cuerpo: Data) -> ErrorApp {
        let p = try? JSONDecoder().decode(ProblemaDTO.self, from: cuerpo)
        let campos = p?.errores?.compactMap(\.mensaje).filter { !$0.isEmpty }.joined(separator: "\n")
        let texto = (campos?.isEmpty == false ? campos : nil) ?? p?.detail ?? p?.title
        switch status {
        case 400, 422: return ErrorApp(tipo: .validacion, mensaje: texto ?? "Revise los datos.", motivo: p?.motivo)
        case 401: return .sesionVencida
        case 403: return ErrorApp(tipo: .prohibido, mensaje: texto ?? "No tiene permiso para esta acción.", motivo: p?.motivo)
        case 404: return ErrorApp(tipo: .noEncontrado, mensaje: texto ?? "No se encontró.", motivo: p?.motivo)
        case 409: return ErrorApp(tipo: .conflicto, mensaje: texto ?? "La operación no es posible ahora.", motivo: p?.motivo)
        case 413: return ErrorApp(tipo: .validacion, mensaje: "La foto supera 5 MB.", motivo: p?.motivo)
        case 429: return ErrorApp(tipo: .limite, mensaje: "Demasiados intentos. Espere un momento e intente de nuevo.", motivo: p?.motivo)
        default:
            let referencia = p?.correlationId.map { " (referencia \($0))" } ?? ""
            return ErrorApp(tipo: .servidor, mensaje: "El servicio no está disponible. Intente más tarde." + referencia, motivo: p?.motivo)
        }
    }
}

/// Cuerpo multipart/form-data.
struct Multipart {
    let limite = "servired-\(UUID().uuidString)"
    private(set) var datos = Data()

    var tipoContenido: String { "multipart/form-data; boundary=\(limite)" }

    mutating func parte(nombre: String, archivo: String? = nil, tipo: String, contenido: Data) {
        datos.append(Data("--\(limite)\r\n".utf8))
        let nombreArchivo = archivo.map { "; filename=\"\($0)\"" } ?? ""
        datos.append(Data("Content-Disposition: form-data; name=\"\(nombre)\"\(nombreArchivo)\r\n".utf8))
        datos.append(Data("Content-Type: \(tipo)\r\n\r\n".utf8))
        datos.append(contenido)
        datos.append(Data("\r\n".utf8))
    }

    func cuerpo() -> Data { datos + Data("--\(limite)--\r\n".utf8) }
}

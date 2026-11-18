import CryptoKit
import Foundation

/**
 Reportes del ciudadano, con soporte sin conexión (igual que en Android):
 - "Mis reportes" se lee de SwiftData y se actualiza desde el servidor.
 - Un reporte nuevo se guarda primero en el teléfono (outbox) con un
   correlationId fijo y luego se envía. Si no hay red, se envía al volver la
   conexión o en segundo plano; repetirlo con el mismo correlationId no lo
   duplica.
 - Cada foto tiene su propio correlationId derivado del reporte, también estable.
 */
actor RepositorioReportesImpl: RepositorioReportes {
    private let api: ClienteAPI
    private let almacen: AlmacenLocal
    private let fotos: CarpetaFotos
    private let programador: ProgramadorEnvio
    private let ahora: @Sendable () -> Date
    private var envio: Task<Bool, Never>?

    init(api: ClienteAPI, almacen: AlmacenLocal, fotos: CarpetaFotos, programador: ProgramadorEnvio,
         ahora: @escaping @Sendable () -> Date = Date.init) {
        self.api = api
        self.almacen = almacen
        self.fotos = fotos
        self.programador = programador
        self.ahora = ahora
    }

    func misReportes() async -> [Reporte] {
        await almacen.reportes().compactMap { try? JSONDecoder().decode(ReporteDTO.self, from: $0.json).dominio() }
    }

    func pendientes() async -> [ReportePendiente] {
        await almacen.pendientes().compactMap { p in
            guard let datos = try? JSONDecoder().decode(NuevoReporteDTO.self, from: p.json).dominio() else { return nil }
            return ReportePendiente(idLocal: p.idLocal, correlationId: p.correlationId, datos: datos, fotos: p.fotos,
                                    estado: EstadoEnvio(rawValue: p.estado) ?? .PENDIENTE, error: p.error,
                                    creadoEn: p.creadoEn, idReporte: p.idReporte)
        }
    }

    func actualizar() async throws {
        var todos: [ReporteDTO] = []
        var pagina = 0
        while pagina < 10 {
            let p: PaginaReportesDTO = try await api.json(Peticion(metodo: "GET", ruta: "api/v1/reportes",
                consulta: [URLQueryItem(name: "pagina", value: "\(pagina)"), URLQueryItem(name: "tamano", value: "50")]))
            todos += p.elementos
            pagina += 1
            if todos.count >= p.total || p.elementos.isEmpty { break }
        }
        await almacen.reemplazar(todos.compactMap(fila))
    }

    func obtener(_ id: String) async throws -> Reporte {
        let dto: ReporteDTO = try await api.json(Peticion(metodo: "GET", ruta: "api/v1/reportes/\(id)"))
        return try await guardarYConvertir(dto)
    }

    func crear(_ nuevo: NuevoReporte, fotos nombres: [String]) async throws -> Bool {
        if let error = Reglas.validarReporte(nuevo, ahora: ahora()).first { throw ErrorApp.validacion(error) }
        if nombres.count > Reglas.maxFotos { throw ErrorApp.validacion("Máximo \(Reglas.maxFotos) fotos.") }
        await almacen.insertar(FilaPendiente(
            correlationId: UUID().uuidString.lowercased(),
            json: try JSONEncoder().encode(nuevo.dto),
            fotos: nombres,
            estado: EstadoEnvio.PENDIENTE.rawValue,
            creadoEn: ahora()
        ))
        let enviado = await enviarPendientes()
        if !enviado { programador.programar() }
        return enviado
    }

    @discardableResult
    func enviarPendientes() async -> Bool {
        // Un solo envío a la vez; si había uno en curso, se espera y se vuelve a revisar.
        while let enCurso = envio { _ = await enCurso.value }
        let tarea = Task { await self.procesar() }
        envio = tarea
        let resultado = await tarea.value
        envio = nil
        return resultado
    }

    private func procesar() async -> Bool {
        var todoListo = true
        for p in await almacen.pendientes(soloPorEnviar: true) where !(await enviar(p)) {
            todoListo = false
        }
        return todoListo
    }

    /// true si el pendiente quedó resuelto (enviado o fallido definitivo).
    private func enviar(_ inicial: FilaPendiente) async -> Bool {
        var p = inicial
        p.estado = EstadoEnvio.ENVIANDO.rawValue
        p.error = nil
        await almacen.actualizar(p)
        if p.idReporte == nil {
            do {
                let creado: ReporteDTO = try await api.json(Peticion(metodo: "POST", ruta: "api/v1/reportes", cuerpo: p.json,
                                                                       tipoContenido: "application/json", correlacion: p.correlationId))
                _ = try? await guardarYConvertir(creado)
                p.idReporte = creado.idReporte
                await almacen.actualizar(p)
            } catch {
                return await detener(p, ErrorApp.de(error))
            }
        }
        while p.fotosEnviadas < p.fotos.count {
            let i = p.fotosEnviadas
            if let datos = fotos.datos(p.fotos[i]) {
                do {
                    let r: ReporteDTO = try await api.json(Self.peticionFoto(idReporte: p.idReporte!, datos: datos,
                                                                              correlacion: Self.correlacionFoto(p.correlationId, i)))
                    _ = try? await guardarYConvertir(r)
                } catch {
                    // Una foto rechazada (inválida, muy grande o ya hay 3) no bloquea el reporte.
                    let e = ErrorApp.de(error)
                    if Self.reintentable(e) { return await detener(p, e) }
                }
            }
            p.fotosEnviadas = i + 1
            await almacen.actualizar(p)
        }
        await almacen.borrarPendiente(p.idLocal)
        p.fotos.forEach(fotos.borrar)
        return true
    }

    private func detener(_ inicial: FilaPendiente, _ error: ErrorApp) async -> Bool {
        var p = inicial
        p.error = error.mensaje
        let reintentar = Self.reintentable(error)
        p.estado = (reintentar ? EstadoEnvio.PENDIENTE : .FALLIDO).rawValue
        await almacen.actualizar(p)
        return !reintentar
    }

    private static func reintentable(_ e: ErrorApp) -> Bool { [.sinConexion, .servidor, .limite, .sesion].contains(e.tipo) }

    func descartarPendiente(_ idLocal: UUID) async {
        if let p = await almacen.pendiente(idLocal) { p.fotos.forEach(fotos.borrar) }
        await almacen.borrarPendiente(idLocal)
    }

    func adjuntarFoto(idReporte: String, foto: String) async throws -> Reporte {
        defer { fotos.borrar(foto) }
        guard let datos = fotos.datos(foto) else { throw ErrorApp.validacion("No se pudo leer la foto.") }
        let r: ReporteDTO = try await api.json(Self.peticionFoto(idReporte: idReporte, datos: datos, correlacion: nil))
        return try await guardarYConvertir(r)
    }

    func foto(idReporte: String, idEvidencia: String) async throws -> Data {
        try await api.ejecutar(Peticion(metodo: "GET", ruta: "api/v1/reportes/\(idReporte)/evidencias/\(idEvidencia)")).0
    }

    func restablecer(_ id: String, fecha: Date, comentario: String?) async throws -> Reporte {
        // Se consulta el reporte vigente para validar contra su hora de inicio.
        let actual = try await obtener(id)
        if let e = Reglas.validarRestablecimiento(fecha: fecha, inicio: actual.inicioEstimado, ahora: ahora(), comentario: comentario) {
            throw ErrorApp.validacion(e)
        }
        let texto = comentario?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cuerpo = NuevoRestablecimientoDTO(fechaRestablecimiento: Fechas.texto(fecha), comentario: texto?.isEmpty == false ? texto : nil)
        let r: ReporteDTO = try await api.json(.json("POST", "api/v1/reportes/\(id)/restablecimiento", cuerpo))
        return try await guardarYConvertir(r)
    }

    func cercanos(desde u: Ubicacion) async throws -> [ReporteCercano] {
        let lista: [ReporteCercanoDTO] = try await api.json(Peticion(metodo: "GET", ruta: "api/v1/reportes/cercanos", consulta: [
            URLQueryItem(name: "latitud", value: "\(u.latitud)"), URLQueryItem(name: "longitud", value: "\(u.longitud)"),
        ]))
        return try lista.map { try $0.dominio() }
    }

    func zonasPublicas() async throws -> [Zona] {
        let m: MapaPublicoDTO = try await api.json(Peticion(metodo: "GET", ruta: "api/v1/mapas/publico"))
        return m.celdas.map { Zona(ubicacion: Ubicacion(latitud: $0.latitud, longitud: $0.longitud), reportes: $0.reportes) }
    }

    func validar(_ id: String, objeto: ObjetoValidacion, sentido: Sentido, desde u: Ubicacion) async throws -> ResultadoValidacion {
        do {
            let (datos, respuesta) = try await api.ejecutar(.json("POST", "api/v1/reportes/\(id)/validaciones",
                                                                  NuevaValidacionDTO(objeto: objeto, sentido: sentido, ubicacion: u.dto)))
            let v = try JSONDecoder().decode(ValidacionDTO.self, from: datos)
            return ResultadoValidacion(objeto: v.objeto,
                                       conteo: Conteo(confirmaciones: v.confirmaciones, controversias: v.controversias, netas: v.confirmacionesNetas),
                                       nueva: respuesta.statusCode == 201)
        } catch let e as ErrorApp where e.tipo == .conflicto {
            throw Self.motivoValidacion(e)
        }
    }

    private func guardarYConvertir(_ dto: ReporteDTO) async throws -> Reporte {
        if let f = fila(dto) { await almacen.guardar([f]) }
        return try dto.dominio()
    }

    private nonisolated func fila(_ dto: ReporteDTO) -> FilaReporte? {
        guard let json = try? JSONEncoder().encode(dto), let fecha = Fechas.desde(dto.fechaRegistro) else { return nil }
        return FilaReporte(id: dto.idReporte, json: json, fechaRegistro: fecha)
    }

    /// Mismo reporte y misma foto -> mismo correlationId en cada reintento (UUID v5-like, determinista).
    static func correlacionFoto(_ correlacionReporte: String, _ indice: Int) -> String {
        var b = Array(Insecure.SHA1.hash(data: Data("\(correlacionReporte)/foto/\(indice)".utf8)).prefix(16))
        b[6] = (b[6] & 0x0F) | 0x50
        b[8] = (b[8] & 0x3F) | 0x80
        return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
            .uuidString.lowercased()
    }

    static func peticionFoto(idReporte: String, datos: Data, correlacion: String?) -> Peticion {
        var m = Multipart()
        m.parte(nombre: "foto", archivo: "foto.jpg", tipo: "image/jpeg", contenido: datos)
        return Peticion(metodo: "POST", ruta: "api/v1/reportes/\(idReporte)/evidencias", cuerpo: m.cuerpo(),
                        tipoContenido: m.tipoContenido, correlacion: correlacion ?? UUID().uuidString.lowercased())
    }

    static func motivoValidacion(_ e: ErrorApp) -> ErrorApp {
        var r = e
        switch e.motivo {
        case "AUTOR": r.mensaje = "No puede validar su propio reporte."
        case "LEJOS": r.mensaje = "Debe estar a menos de 1 km del reporte para validarlo."
        case "CERRADO": r.mensaje = "El reporte ya está cerrado."
        case "SIN_RESTABLECIMIENTO": r.mensaje = "Aún nadie reportó que el servicio volvió."
        default: break
        }
        return r
    }
}

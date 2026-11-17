import Foundation
import Testing
@testable import ServiRed

@Suite("Reportes sin conexión")
struct RepositorioReportesTests {
    let transporte = TransporteFalso()
    let programador = ProgramadorFalso()
    let carpeta: CarpetaFotos
    let repo: RepositorioReportesImpl
    static let ahora = Date(timeIntervalSince1970: 1_795_000_000)

    let nuevo = NuevoReporte(tipoServicio: .ENERGIA, ubicacion: Ubicacion(latitud: 4.6287, longitud: -74.0648),
                             inicioEstimado: Self.ahora.addingTimeInterval(-3600), magnitud: .CUADRA, descripcion: "Sin luz")

    init() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        carpeta = CarpetaFotos(url: dir)
        let gestor = GestorTokens(cofre: CofreMemoria()) { _ in .rechazada }
        await gestor.guardar(sesionDTO("A1", "R1"))
        let almacen = AlmacenLocal(modelContainer: try AlmacenLocal.contenedor(enMemoria: true))
        repo = RepositorioReportesImpl(api: ClienteAPI(base: base, transporte: transporte, gestor: gestor), almacen: almacen,
                                       fotos: carpeta, programador: programador, ahora: { Self.ahora })
    }

    func foto() throws -> String {
        let n = "\(UUID().uuidString).jpg"
        try Data([0xFF, 0xD8, 0xFF]).write(to: carpeta.ruta(n))
        return n
    }

    @Test func conRedElReporteSeEnviaYQuedaEnMisReportes() async throws {
        await transporte.encolar(.http(201, reporteJSON("r-1")))
        #expect(try await repo.crear(nuevo, fotos: []) == true)
        #expect(await repo.pendientes().isEmpty)
        #expect(await repo.misReportes().map(\.id) == ["r-1"])
        let cuerpo = try #require(await transporte.peticiones.first).cuerpoTexto
        #expect(cuerpo.contains(#""inicioEstimado":"2026-11-18T"#), "la hora viaja con zona")
        #expect(cuerpo.range(of: #"(Z|[+-]\d\d:\d\d)""#, options: .regularExpression) != nil)
    }

    @Test func sinRedQuedaPendienteYElReintentoUsaElMismoCorrelationId() async throws {
        await transporte.encolar(.falla(.notConnectedToInternet))
        #expect(try await repo.crear(nuevo, fotos: []) == false)
        #expect(programador.contador.valor == 1)
        let pendiente = try #require(await repo.pendientes().first)
        #expect(pendiente.estado == .PENDIENTE)
        let primero = await transporte.peticiones[0].cabecera("X-Correlation-Id")
        #expect(primero == pendiente.correlationId)

        await transporte.encolar(.http(201, reporteJSON("r-1")))
        #expect(await repo.enviarPendientes())
        #expect(await transporte.peticiones[1].cabecera("X-Correlation-Id") == primero)
        #expect(await repo.pendientes().isEmpty)
    }

    @Test func unRechazoPorDatosInvalidosQuedaFallidoYNoSeReintenta() async throws {
        await transporte.encolar(.http(400, #"{"title":"Solicitud inválida","errores":[{"campo":"ubicacion","mensaje":"Fuera de Colombia"}]}"#))
        _ = try await repo.crear(nuevo, fotos: [])
        let p = try #require(await repo.pendientes().first)
        #expect(p.estado == .FALLIDO)
        #expect(p.error == "Fuera de Colombia")
        #expect(await repo.enviarPendientes(), "no queda nada por reintentar")
        await repo.descartarPendiente(p.idLocal)
        #expect(await repo.pendientes().isEmpty)
    }

    @Test func lasFotosSeSubenDespuesDelReporteConCorrelationIdEstables() async throws {
        let fotos = [try foto(), try foto()]
        await transporte.encolar(.http(201, reporteJSON("r-1")), .http(201, reporteJSON("r-1")), .falla(.notConnectedToInternet))
        #expect(try await repo.crear(nuevo, fotos: fotos) == false)
        let p = await transporte.peticiones
        #expect(p[1].url?.path == "/api/v1/reportes/r-1/evidencias")
        let cid = try #require(p[0].cabecera("X-Correlation-Id"))
        #expect(p[1].cabecera("X-Correlation-Id") == RepositorioReportesImpl.correlacionFoto(cid, 0))
        #expect(p[1].cuerpoTexto.contains("filename=\"foto.jpg\""))

        await transporte.encolar(.http(201, reporteJSON("r-1")))
        #expect(await repo.enviarPendientes())
        let reintento = await transporte.peticiones[3]
        #expect(reintento.url?.path == "/api/v1/reportes/r-1/evidencias", "solo se reintenta la foto que faltaba")
        #expect(reintento.cabecera("X-Correlation-Id") == p[2].cabecera("X-Correlation-Id"))
        #expect(fotos.allSatisfy { !FileManager.default.fileExists(atPath: carpeta.ruta($0).path) })
    }

    @Test func elIdDeCadaFotoEsDeterministaYDistinto() {
        let a = RepositorioReportesImpl.correlacionFoto("abc", 0)
        #expect(a == RepositorioReportesImpl.correlacionFoto("abc", 0))
        #expect(a != RepositorioReportesImpl.correlacionFoto("abc", 1))
        #expect(UUID(uuidString: a) != nil)
    }

    @Test func noSeGuardaUnReporteConHoraFutura() async {
        var futuro = nuevo
        futuro.inicioEstimado = Self.ahora.addingTimeInterval(3600)
        await #expect(throws: ErrorApp.self) { try await repo.crear(futuro, fotos: []) }
        #expect(await repo.pendientes().isEmpty)
        #expect(await transporte.peticiones.isEmpty)
    }

    @Test func validarTraduceLosMotivosDelServidor() async throws {
        await transporte.encolar(.http(409, #"{"title":"Conflicto","motivo":"LEJOS"}"#),
                                 .http(201, #"{"idReporte":"r-9","objeto":"CORTE","confirmaciones":3,"controversias":0,"confirmacionesNetas":3,"validado":true}"#))
        let aqui = Ubicacion(latitud: 4.6, longitud: -74.0)
        do {
            _ = try await repo.validar("r-9", objeto: .CORTE, sentido: .CONFIRMA, desde: aqui)
            Issue.record("debió fallar")
        } catch let e as ErrorApp {
            #expect(e.mensaje == "Debe estar a menos de 1 km del reporte para validarlo.")
        }
        let v = try await repo.validar("r-9", objeto: .CORTE, sentido: .CONFIRMA, desde: aqui)
        #expect(v.nueva && v.conteo.validado)
    }
}

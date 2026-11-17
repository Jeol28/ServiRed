import Foundation
import Testing
@testable import ServiRed

@MainActor
final class SesionFalsa: RepositorioSesion {
    var estado: EstadoSesion = .anonima
    var llamadas: [String] = []
    func restaurar() async {}
    func ingresar(correo: String, clave: String) async throws { llamadas.append("ingresar:\(correo)") }
    func registrar(correo: String, clave: String, nombre: String?) async throws { llamadas.append("registrar:\(correo)") }
    func cerrar() async {}
}

actor ReportesFalso: RepositorioReportes {
    var creados: [NuevoReporte] = []
    var enviar = true
    func ponerEnviar(_ v: Bool) { enviar = v }
    func misReportes() async -> [Reporte] { [] }
    func pendientes() async -> [ReportePendiente] { [] }
    func actualizar() async throws {}
    func obtener(_ id: String) async throws -> Reporte { throw ErrorApp.sinConexion }
    func crear(_ nuevo: NuevoReporte, fotos: [String]) async throws -> Bool { creados.append(nuevo); return enviar }
    func enviarPendientes() async -> Bool { true }
    func descartarPendiente(_ idLocal: UUID) async {}
    func adjuntarFoto(idReporte: String, foto: String) async throws -> Reporte { throw ErrorApp.sinConexion }
    func foto(idReporte: String, idEvidencia: String) async throws -> Data { Data() }
    func restablecer(_ id: String, fecha: Date, comentario: String?) async throws -> Reporte { throw ErrorApp.sinConexion }
    func cercanos(desde: Ubicacion) async throws -> [ReporteCercano] { [] }
    func validar(_ id: String, objeto: ObjetoValidacion, sentido: Sentido, desde: Ubicacion) async throws -> ResultadoValidacion {
        throw ErrorApp.sinConexion
    }
}

actor DenunciasFalso: RepositorioDenuncias {
    var enviadas: [NuevaDenuncia] = []
    func enviar(_ denuncia: NuevaDenuncia, foto: String?) async throws { enviadas.append(denuncia) }
}

@Suite("Modelos de vista")
@MainActor
struct ModelosVistaTests {
    let ahora = Date(timeIntervalSince1970: 1_795_000_000)

    @Test func elRegistroExigeCorreoValidoClaveAdecuadaYAutorizacionDeDatos() async {
        let sesion = SesionFalsa()
        let m = IngresoModelo(sesion: sesion)
        m.alternarModo()
        m.correo = "ana@correo.co"
        m.clave = "una clave larga"
        await m.enviar()
        #expect(m.error?.contains("autorizar") == true)
        m.aceptaTratamiento = true
        await m.enviar()
        #expect(sesion.llamadas == ["registrar:ana@correo.co"])
        #expect(m.clave.isEmpty)
    }

    @Test func elReporteNuevoPideServicioMagnitudYUbicacion() async {
        let repo = ReportesFalso()
        let m = NuevoReporteModelo(repo: repo, ubicacion: UbicacionFija(u: nil), ahora: { self.ahora })
        await m.enviar()
        #expect(m.error == "Elija el servicio afectado.")
        m.tipo = .GAS
        m.magnitud = .BARRIO
        await m.usarMiUbicacion()
        #expect(m.error?.contains("mapa") == true)
        m.ubicacion = Ubicacion(latitud: 3.45, longitud: -76.53)
        m.minutosAtras = 90
        await m.enviar()
        #expect(await repo.creados.first?.inicioEstimado == ahora.addingTimeInterval(-5400))
        #expect(m.resultado == .enviado)
    }

    @Test func sinRedElReporteQuedaPendiente() async {
        let repo = ReportesFalso()
        await repo.ponerEnviar(false)
        let m = NuevoReporteModelo(repo: repo, ubicacion: UbicacionFija(), ahora: { self.ahora })
        await m.usarMiUbicacion()
        m.tipo = .ENERGIA
        m.magnitud = .VIVIENDA
        await m.enviar()
        #expect(m.resultado == .pendiente)
    }

    @Test func maximoTresFotosYLaQueSobraSeDescarta() {
        var descartadas: [String] = []
        let m = NuevoReporteModelo(repo: ReportesFalso(), ubicacion: UbicacionFija(), descartarFoto: { descartadas.append($0) })
        (1...4).forEach { m.agregarFoto("f\($0)") }
        #expect(m.fotos == ["f1", "f2", "f3"])
        #expect(descartadas == ["f4"])
        m.quitarFoto("f2")
        #expect(m.fotos == ["f1", "f3"])
    }

    @Test func sinUbicacionNoSeBuscanReportesCercanos() async {
        let m = CercanosModelo(repo: ReportesFalso(), ubicacion: UbicacionFija(u: nil))
        await m.buscar()
        #expect(m.error?.contains("ubicación") == true)
    }

    @Test func alEnviarLaDenunciaSeOlvidaSuContenido() async {
        let repo = DenunciasFalso()
        let m = DenunciaModelo(repo: repo, ubicacion: UbicacionFija())
        m.tipo = .ACUEDUCTO
        m.descripcion = "corto"
        await m.usarMiUbicacion()
        await m.enviar()
        #expect(m.error?.contains("entre 10") == true)
        m.descripcion = "Toma ilegal de agua en la esquina"
        await m.enviar()
        #expect(await repo.enviadas.count == 1)
        #expect(m.enviada)
        #expect(m.descripcion.isEmpty && m.ubicacion == nil && m.tipo == nil)
    }
}

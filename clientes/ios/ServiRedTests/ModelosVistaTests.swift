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
    func cerrar() async { llamadas.append("cerrar") }
}

actor ReportesFalso: RepositorioReportes {
    var creados: [NuevoReporte] = []
    var enviar = true
    var cercanosFalsos: [ReporteCercano] = []
    var mios: [Reporte] = []
    var restablecidos: [String] = []
    var votos: [String] = []
    func ponerEnviar(_ v: Bool) { enviar = v }
    func ponerCercanos(_ c: [ReporteCercano]) { cercanosFalsos = c }
    func ponerMios(_ m: [Reporte]) { mios = m }
    func misReportes() async -> [Reporte] { mios }
    func pendientes() async -> [ReportePendiente] { [] }
    var actualizaciones = 0
    func actualizar() async throws { actualizaciones += 1 }
    func obtener(_ id: String) async throws -> Reporte { throw ErrorApp.sinConexion }
    func crear(_ nuevo: NuevoReporte, fotos: [String]) async throws -> Bool { creados.append(nuevo); return enviar }
    func enviarPendientes() async -> Bool { true }
    func descartarPendiente(_ idLocal: UUID) async {}
    func adjuntarFoto(idReporte: String, foto: String) async throws -> Reporte { throw ErrorApp.sinConexion }
    func foto(idReporte: String, idEvidencia: String) async throws -> Data { Data() }
    func restablecer(_ id: String, fecha: Date, comentario: String?) async throws -> Reporte {
        restablecidos.append(id)
        guard let i = mios.firstIndex(where: { $0.id == id }) else { throw ErrorApp.sinConexion }
        mios[i].fechaRestablecimiento = fecha
        mios[i].estado = .RESTABLECIDO
        return mios[i]
    }
    func cercanos(desde: Ubicacion) async throws -> [ReporteCercano] { cercanosFalsos }
    func zonasPublicas() async throws -> [Zona] { [Zona(ubicacion: Ubicacion(latitud: 3.45, longitud: -76.53), reportes: 7)] }
    func validar(_ id: String, objeto: ObjetoValidacion, sentido: Sentido, desde: Ubicacion) async throws -> ResultadoValidacion {
        votos.append(id)
        return ResultadoValidacion(objeto: objeto, conteo: Conteo(confirmaciones: 3, controversias: 0, netas: 3), nueva: true)
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
        let m = IngresoModelo(sesion: sesion, registro: true)
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

    let aqui = Ubicacion(latitud: 4.6287, longitud: -74.0648)

    func cercano(_ id: String) -> ReporteCercano {
        ReporteCercano(id: id, tipoServicio: .ENERGIA, magnitud: .CUADRA, estado: .RECIBIDO, inicioEstimado: ahora.addingTimeInterval(-7200),
                       ubicacion: Ubicacion(latitud: 4.629, longitud: -74.065), distanciaMetros: 300,
                       corte: Conteo(confirmaciones: 1, controversias: 0, netas: 1), restablecimiento: nil)
    }

    func propio(_ id: String) -> Reporte {
        Reporte(id: id, tipoServicio: .ACUEDUCTO, ubicacion: aqui, codigoMunicipio: "11001", nombreMunicipio: "BOGOTÁ, D.C.",
                inicioEstimado: ahora.addingTimeInterval(-3600), magnitud: .BARRIO, descripcion: nil, estado: .RECIBIDO,
                fechaRegistro: ahora, fechaRestablecimiento: nil, duracionMinutos: nil, validacionCorte: nil,
                validacionRestablecimiento: nil, evidencias: [], historia: [])
    }

    @Test func elMapaCargaUbicacionCortesCercanosYZonasDelPais() async {
        let repo = ReportesFalso()
        await repo.ponerCercanos([cercano("c1"), cercano("c2")])
        let m = MapaModelo(repo: repo, ubicacion: UbicacionFija(u: aqui), ahora: { self.ahora })
        await m.refrescar()
        #expect(m.ubicacion == aqui)
        #expect(m.cercanos.map(\.id) == ["c1", "c2"])
        #expect(m.zonas.first?.reportes == 7)
        #expect(!m.cargando)
    }

    @Test func sinCuentaElMapaMuestraLasZonasDelPaisPeroNoLosCortesExactos() async {
        let repo = ReportesFalso()
        await repo.ponerCercanos([cercano("c1")])
        let m = MapaModelo(repo: repo, ubicacion: UbicacionFija(u: aqui), ahora: { self.ahora }, anonimo: true)
        await m.refrescar()
        #expect(m.ubicacion == aqui)
        #expect(m.zonas.first?.reportes == 7)
        #expect(m.cercanos.isEmpty, "los cortes exactos son solo para quien tiene cuenta (RNF-DAT2)")
        #expect(await repo.actualizaciones == 0)
    }

    @Test func sinUbicacionElMapaLoAvisaYNoBuscaCortesCercanos() async {
        let repo = ReportesFalso()
        await repo.ponerCercanos([cercano("c1")])
        let m = MapaModelo(repo: repo, ubicacion: UbicacionFija(u: nil))
        await m.refrescar()
        #expect(m.sinUbicacion)
        #expect(m.cercanos.isEmpty)
    }

    @Test func confirmarUnCorteCercanoLoCuentaYCierraLaTarjeta() async {
        let repo = ReportesFalso()
        await repo.ponerCercanos([cercano("c1")])
        let m = MapaModelo(repo: repo, ubicacion: UbicacionFija(u: aqui))
        await m.refrescar()
        m.seleccionar("c:c1")
        #expect(m.cercanoSeleccionado?.id == "c1")
        await m.validar(.CONFIRMA)
        #expect(await repo.votos == ["c1"])
        #expect(m.seleccion == nil)
        #expect(m.cercanos.first?.corte?.validado == true)
        #expect(m.aviso?.contains("Gracias") == true)
    }

    @Test func conUnCortePropioActivoPreguntaSiYaVolvioYRespondeEnUnToque() async {
        let repo = ReportesFalso()
        await repo.ponerMios([propio("r1"), propio("r2")])
        let m = MapaModelo(repo: repo, ubicacion: UbicacionFija(u: aqui), ahora: { self.ahora })
        await m.cargarLocal()
        #expect(m.preguntaVolvio?.id == "r1")
        m.todaviaNo("r1")
        #expect(m.preguntaVolvio?.id == "r2")
        await m.yaVolvio("r2")
        #expect(await repo.restablecidos == ["r2"])
        #expect(m.preguntaVolvio == nil, "ya no hay cortes propios activos sin responder")
        #expect(m.aviso?.contains("volvió") == true)
    }

    @Test func seleccionarUnaMarcaPropiaOcultaLaPreguntaYDeseleccionarLaVuelveAMostrar() async {
        let repo = ReportesFalso()
        await repo.ponerMios([propio("r1")])
        let m = MapaModelo(repo: repo, ubicacion: UbicacionFija(u: aqui))
        await m.cargarLocal()
        m.seleccionar("m:r1")
        #expect(m.propioSeleccionado?.id == "r1")
        #expect(m.preguntaVolvio == nil)
        m.seleccionar(nil)
        #expect(m.preguntaVolvio?.id == "r1")
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

actor CuentaFalsa: RepositorioCuenta {
    func yo() async throws -> Cuenta { Cuenta(id: "c1", correo: "ana@correo.co", nombre: "Ana", rol: "CIUDADANO") }
    func cambiarNombre(_ nombre: String) async throws -> Cuenta { throw ErrorApp.sinConexion }
    func cambiarClave(actual: String, nueva: String) async throws {}
    func eliminar() async throws {}
}

/// Pendientes en memoria: con red se envían los que esperan; los rechazados quedan.
actor ReportesConPendientes: RepositorioReportes {
    var lista: [ReportePendiente]
    var hayRed = true
    var envios = 0
    init(_ estados: [EstadoEnvio]) {
        lista = estados.map { e in
            ReportePendiente(idLocal: UUID(), correlationId: UUID().uuidString,
                             datos: NuevoReporte(tipoServicio: .ENERGIA, ubicacion: Ubicacion(latitud: 4.6, longitud: -74.1),
                                                 inicioEstimado: Date(timeIntervalSince1970: 0), magnitud: .CUADRA, descripcion: nil),
                             fotos: [], estado: e, error: e == .FALLIDO ? "Datos inválidos" : nil,
                             creadoEn: Date(timeIntervalSince1970: 0), idReporte: nil)
        }
    }
    func ponerRed(_ v: Bool) { hayRed = v }
    func vaciar() { lista = [] }
    func pendientes() async -> [ReportePendiente] { lista }
    func enviarPendientes() async -> Bool {
        envios += 1
        if hayRed { lista = lista.filter { $0.estado == .FALLIDO } }
        return !lista.contains { $0.estado != .FALLIDO }
    }
    func misReportes() async -> [Reporte] { [] }
    func actualizar() async throws {}
    func obtener(_ id: String) async throws -> Reporte { throw ErrorApp.sinConexion }
    func crear(_ nuevo: NuevoReporte, fotos: [String]) async throws -> Bool { true }
    func descartarPendiente(_ idLocal: UUID) async { lista.removeAll { $0.idLocal == idLocal } }
    func adjuntarFoto(idReporte: String, foto: String) async throws -> Reporte { throw ErrorApp.sinConexion }
    func foto(idReporte: String, idEvidencia: String) async throws -> Data { Data() }
    func restablecer(_ id: String, fecha: Date, comentario: String?) async throws -> Reporte { throw ErrorApp.sinConexion }
    func cercanos(desde: Ubicacion) async throws -> [ReporteCercano] { [] }
    func zonasPublicas() async throws -> [Zona] { [] }
    func validar(_ id: String, objeto: ObjetoValidacion, sentido: Sentido, desde: Ubicacion) async throws -> ResultadoValidacion {
        throw ErrorApp.sinConexion
    }
}

@Suite("Cerrar sesión con reportes pendientes")
@MainActor
struct CierreDeSesionTests {
    @Test func sinPendientesCierraDeInmediato() async {
        let sesion = SesionFalsa()
        let reportes = ReportesConPendientes([])
        await CuentaModelo(repo: CuentaFalsa(), sesion: sesion, reportes: reportes).cerrarSesion()
        #expect(sesion.llamadas == ["cerrar"])
        #expect(await reportes.envios == 0)
    }

    @Test func antesDeCerrarEnviaLosPendientesYSiSalenCierra() async {
        let sesion = SesionFalsa()
        let reportes = ReportesConPendientes([.PENDIENTE, .PENDIENTE])
        let m = CuentaModelo(repo: CuentaFalsa(), sesion: sesion, reportes: reportes)
        await m.cerrarSesion()
        #expect(await reportes.envios == 1)
        #expect(await reportes.lista.isEmpty)
        #expect(sesion.llamadas == ["cerrar"])
        #expect(m.cierreBloqueado == nil)
    }

    @Test func sinRedNoCierraNiBorraYExplicaPorQue() async throws {
        let sesion = SesionFalsa()
        let reportes = ReportesConPendientes([.PENDIENTE])
        await reportes.ponerRed(false)
        let m = CuentaModelo(repo: CuentaFalsa(), sesion: sesion, reportes: reportes)
        await m.cerrarSesion()
        #expect(sesion.llamadas.isEmpty)
        #expect(await reportes.lista.count == 1)
        let motivo = try #require(m.cierreBloqueado)
        #expect(motivo.hasPrefix("Tiene 1 reporte sin enviar, que se borraría"))
        #expect(motivo.contains("1 aún no se pudo enviar"))
        #expect(!m.ocupado && !m.enviandoPendientes)

        m.cierreBloqueado = nil
        await reportes.ponerRed(true)
        await m.cerrarSesion()
        #expect(sesion.llamadas == ["cerrar"])
    }

    @Test func unRechazadoTambienBloqueaHastaDescartarlo() async throws {
        let sesion = SesionFalsa()
        let reportes = ReportesConPendientes([.PENDIENTE, .FALLIDO, .FALLIDO])
        let m = CuentaModelo(repo: CuentaFalsa(), sesion: sesion, reportes: reportes)
        await m.cerrarSesion()
        #expect(sesion.llamadas.isEmpty)
        let motivo = try #require(m.cierreBloqueado)
        #expect(motivo.hasPrefix("Tiene 2 reportes sin enviar, que se borrarían"))
        #expect(motivo.contains("2 fueron rechazados por el servidor: revíselos en Mis reportes"))
        #expect(!motivo.contains("aún no"))

        await reportes.vaciar()   // el ciudadano los descartó en Mis reportes
        await m.cerrarSesion()
        #expect(sesion.llamadas == ["cerrar"])
    }
}

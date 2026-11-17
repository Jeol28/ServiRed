import Foundation
import Testing
@testable import ServiRed

@Suite("Reglas del dominio")
struct ReglasTests {
    let ahora = Date(timeIntervalSince1970: 1_795_000_000)
    let bogota = Ubicacion(latitud: 4.6287, longitud: -74.0648)

    func reporte(inicio: Date? = nil, u: Ubicacion? = nil, d: String? = nil) -> NuevoReporte {
        NuevoReporte(tipoServicio: .ENERGIA, ubicacion: u ?? bogota, inicioEstimado: inicio ?? ahora, magnitud: .CUADRA, descripcion: d)
    }

    @Test func unReporteValidoNoTieneErrores() {
        #expect(Reglas.validarReporte(reporte(inicio: ahora.addingTimeInterval(-7200)), ahora: ahora).isEmpty)
    }

    @Test func laHoraDeInicioNoPuedeSerFuturaSalvoCincoMinutos() {
        #expect(Reglas.validarReporte(reporte(inicio: ahora.addingTimeInterval(240)), ahora: ahora).isEmpty)
        #expect(Reglas.validarReporte(reporte(inicio: ahora.addingTimeInterval(360)), ahora: ahora) == ["La hora de inicio no puede ser futura."])
    }

    @Test func laHoraDeInicioNoPuedeTenerMasDeTreintaDias() {
        #expect(Reglas.validarReporte(reporte(inicio: ahora.addingTimeInterval(-31 * 86_400)), ahora: ahora).count == 1)
    }

    @Test func laUbicacionDebeEstarEnColombia() {
        #expect(Reglas.validarReporte(reporte(u: Ubicacion(latitud: 40.4, longitud: -3.7)), ahora: ahora) == ["La ubicación debe estar en Colombia."])
    }

    @Test func laDescripcionAdmiteHastaMilCaracteres() {
        #expect(Reglas.validarReporte(reporte(d: String(repeating: "x", count: 1000)), ahora: ahora).isEmpty)
        #expect(Reglas.validarReporte(reporte(d: String(repeating: "x", count: 1001)), ahora: ahora).count == 1)
    }

    @Test func elRestablecimientoEsPosteriorAlInicioYNoFuturo() {
        let inicio = ahora.addingTimeInterval(-3 * 3600)
        #expect(Reglas.validarRestablecimiento(fecha: ahora.addingTimeInterval(-3600), inicio: inicio, ahora: ahora, comentario: nil) == nil)
        #expect(Reglas.validarRestablecimiento(fecha: inicio, inicio: inicio, ahora: ahora, comentario: nil)?.contains("después") == true)
        #expect(Reglas.validarRestablecimiento(fecha: ahora.addingTimeInterval(3600), inicio: inicio, ahora: ahora, comentario: nil)?.contains("futura") == true)
    }

    @Test func laDenunciaExigeEntreDiezYDosMilCaracteres() {
        #expect(Reglas.validarDenuncia(NuevaDenuncia(tipoServicio: .GAS, ubicacion: bogota, descripcion: "   corto   ")).count == 1)
        #expect(Reglas.validarDenuncia(NuevaDenuncia(tipoServicio: .GAS, ubicacion: bogota, descripcion: "Conexión ilegal al poste")).isEmpty)
    }

    @Test func laClaveTieneEntreDiezYSesentaYCuatroCaracteresSetentaYDosBytesYNoEsElCorreo() {
        #expect(Reglas.validarClave("una clave larga", correo: "ana@correo.co") == nil)
        #expect(Reglas.validarClave("corta", correo: "ana@correo.co") != nil)
        #expect(Reglas.validarClave(String(repeating: "ñ", count: 40), correo: "ana@correo.co") != nil) // 80 bytes
        #expect(Reglas.validarClave("Ana@Correo.co", correo: "ana@correo.co") != nil)
    }

    @Test func validadoConTresConfirmacionesNetas() {
        #expect(Conteo(confirmaciones: 4, controversias: 1, netas: 3).validado)
        #expect(!Conteo(confirmaciones: 3, controversias: 1, netas: 2).validado)
    }
}

@Suite("Fechas con zona horaria")
struct FechasTests {
    @Test func seEnvianConLaZonaDelTelefono() {
        let d = Date(timeIntervalSince1970: 1_795_000_000) // 2026-11-18T11:06:40Z
        #expect(Fechas.texto(d, zona: TimeZone(identifier: "America/Bogota")!) == "2026-11-18T06:06:40-05:00")
        #expect(Fechas.texto(d, zona: TimeZone(identifier: "UTC")!) == "2026-11-18T11:06:40Z")
    }

    @Test func seAceptanZOffsetsYFracciones() {
        let esperado = Date(timeIntervalSince1970: 1_795_000_000)
        #expect(Fechas.desde("2026-11-18T11:06:40Z") == esperado)
        #expect(Fechas.desde("2026-11-18T06:06:40-05:00") == esperado)
        #expect(abs(Fechas.desde("2026-11-18T11:06:40.123456Z")!.timeIntervalSince(esperado) - 0.123) < 0.001)
        #expect(Fechas.desde("no es fecha") == nil)
    }
}

import Foundation
import Testing
@testable import ServiRed

/// Solo contra el compose local: esta prueba crea cuentas, reportes y denuncias reales. La base
/// por defecto es el servidor en línea, así que se omite salvo que se apunte al compose:
/// TEST_RUNNER_SERVIRED_API_BASE=http://localhost:8080/ xcodebuild … (o Local.xcconfig).
/// Contra el servidor en línea (Supabase) hay que pedirlo: TEST_RUNNER_SERVIRED_E2E_REMOTO=1.
private let destinoPermitido: Bool = {
    let host = Contenedor.baseConfigurada().host() ?? ""
    return ["localhost", "127.0.0.1"].contains(host) || ProcessInfo.processInfo.environment["SERVIRED_E2E_REMOTO"] == "1"
}()

/// ¿Está arriba el entorno de Docker Compose? (el simulador comparte la red de la Mac).
private let gatewayArriba: Bool = {
    guard destinoPermitido else { return false }
    let semaforo = DispatchSemaphore(value: 0)
    nonisolated(unsafe) var listo = false
    URLSession.shared.dataTask(with: Contenedor.baseConfigurada().appending(path: "readyz")) { _, r, _ in
        listo = (r as? HTTPURLResponse)?.statusCode == 200
        semaforo.signal()
    }.resume()
    _ = semaforo.wait(timeout: .now() + 5)
    return listo
}()

/**
 De punta a punta contra el gateway que usa la app (API_BASE: el compose local o el
 servidor de pruebas). Usa las mismas
 piezas que la app, con un cofre y SwiftData en memoria. Se omite si el
 compose no está arriba.
 */
@Suite("Flujo del ciudadano contra el gateway", .serialized, .enabled(if: gatewayArriba, "requiere el compose local arriba (o SERVIRED_E2E_REMOTO=1)"))
@MainActor
struct FlujoCiudadanoE2E {
    let base = Contenedor.baseConfigurada()

    final class Ciudadano {
        let sesion: SesionServiRed
        let reportes: RepositorioReportesImpl
        let cuenta: RepositorioCuentaImpl
        let correo = "ios-\(UUID().uuidString.prefix(8).lowercased())@prueba.servired.co"

        @MainActor
        init(base: URL) throws {
            let transporte = TransporteURLSession()
            let almacen = AlmacenLocal(modelContainer: try AlmacenLocal.contenedor(enMemoria: true))
            let sesion = SesionServiRed(base: base, transporte: transporte, cofre: CofreMemoria()) { await almacen.borrarTodo() }
            let api = ClienteAPI(base: base, transporte: transporte, gestor: sesion.gestor)
            self.sesion = sesion
            reportes = RepositorioReportesImpl(api: api, almacen: almacen, fotos: CarpetaFotos(url: FileManager.default.temporaryDirectory),
                                               programador: ProgramadorFalso())
            cuenta = RepositorioCuentaImpl(api: api) { [weak sesion] in await sesion?.cerrar() }
        }
    }

    /// El límite de /auth (20/min) es compartido con otras suites e2e: ante un 429 se espera y se reintenta.
    func conReintento(_ operacion: () async throws -> Void) async throws {
        for intento in 1...6 {
            do { return try await operacion() } catch let e as ErrorApp where e.tipo == .limite && intento < 6 {
                try await Task.sleep(for: .seconds(11))
            }
        }
    }

    @Test func registroReporteValidacionRestablecimientoDenunciaYSupresion() async throws {
        let ana = try Ciudadano(base: base)
        try await conReintento { try await ana.sesion.registrar(correo: ana.correo, clave: "una clave larga de prueba", nombre: "Ana") }
        guard case .autenticada = ana.sesion.estado else { Issue.record("sin sesión"); return }

        // Un punto distinto en cada corrida (dentro de Bogotá): «cercanos» ordena por distancia y
        // tiene un tope, y las corridas anteriores dejan muchos reportes en un punto fijo.
        let aqui = Ubicacion(latitud: 4.6287 + Double.random(in: -0.008...0.008),
                             longitud: -74.0648 + Double.random(in: -0.008...0.008))
        let nuevo = NuevoReporte(tipoServicio: .ENERGIA, ubicacion: aqui, inicioEstimado: Date().addingTimeInterval(-7200),
                                 magnitud: .CUADRA, descripcion: "Sin luz (prueba iOS)")
        #expect(try await ana.reportes.crear(nuevo, fotos: []))
        try await ana.reportes.actualizar()
        let reporte = try #require(await ana.reportes.misReportes().first)
        #expect(reporte.codigoMunicipio == "11001")
        #expect(reporte.estado == .RECIBIDO)

        // Otro ciudadano cercano lo ve y confirma el corte.
        let beto = try Ciudadano(base: base)
        try await conReintento { try await beto.sesion.registrar(correo: beto.correo, clave: "otra clave larga de prueba", nombre: nil) }
        let cerca = Ubicacion(latitud: aqui.latitud + 0.0003, longitud: aqui.longitud - 0.0002)   // ~40 m
        #expect(try await beto.reportes.cercanos(desde: cerca).contains { $0.id == reporte.id })
        let voto = try await beto.reportes.validar(reporte.id, objeto: .CORTE, sentido: .CONFIRMA, desde: cerca)
        // Beto es una cuenta recién creada: su voto queda registrado, pero no suma al umbral (ADR-22).
        #expect(voto.nueva && voto.conteo.confirmaciones == 0)
        let repetido = try await beto.reportes.validar(reporte.id, objeto: .CORTE, sentido: .CONFIRMA, desde: cerca)
        #expect(!repetido.nueva, "repetir el voto no lo cuenta dos veces")
        await #expect(throws: ErrorApp.self) {
            try await ana.reportes.validar(reporte.id, objeto: .CORTE, sentido: .CONFIRMA, desde: aqui)
        }

        let restablecido = try await ana.reportes.restablecer(reporte.id, fecha: Date().addingTimeInterval(-600), comentario: "Volvió la luz")
        #expect(restablecido.estado == .RESTABLECIDO)
        #expect((105...115).contains(restablecido.duracionMinutos ?? 0))

        // La vista nacional del mapa (zonas agregadas y anónimas).
        _ = try await ana.reportes.zonasPublicas()

        // Denuncia anónima por el cliente público.
        let denuncias = RepositorioDenunciasImpl(publica: ClienteAPI(base: base, transporte: TransporteURLSession(), gestor: nil),
                                                 fotos: CarpetaFotos(url: FileManager.default.temporaryDirectory))
        try await denuncias.enviar(NuevaDenuncia(tipoServicio: .GAS, ubicacion: aqui, descripcion: "Conexión ilegal de gas (prueba iOS)"), foto: nil)

        // Derecho de supresión: la sesión termina y los datos locales se borran.
        try await ana.cuenta.eliminar()
        #expect(ana.sesion.estado == .anonima)
        #expect(await ana.reportes.misReportes().isEmpty)
        try await beto.cuenta.eliminar()
    }
}

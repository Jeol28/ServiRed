import Foundation
import Testing
@testable import ServiRed

@Suite("Gestor de tokens")
struct GestorTokensTests {
    @Test func elRefrescoSeRetiraDelCofreAntesDeUsarloYSeGuardaElNuevo() async {
        let cofre = CofreMemoria(refresco: "R1")
        let gestor = GestorTokens(cofre: cofre) { r in
            #expect(r == "R1")
            #expect(cofre.leerRefresco() == nil, "ya no está en el cofre mientras se usa")
            return .lista(sesionDTO("A2", "R2"))
        }
        #expect(await gestor.renovar(fallido: nil) == "A2")
        #expect(cofre.leerRefresco() == "R2")
    }

    @Test func renovacionesSimultaneasUsanElRefrescoUnaSolaVez() async {
        let cofre = CofreMemoria(refresco: "R1")
        let llamadas = Contador()
        let gestor = GestorTokens(cofre: cofre) { _ in
            llamadas.sumar()
            try? await Task.sleep(for: .milliseconds(50))
            return .lista(sesionDTO("A2", "R2"))
        }
        let tokens = await withTaskGroup(of: String?.self) { grupo in
            for _ in 0..<8 { grupo.addTask { await gestor.renovar(fallido: "A1") } }
            return await grupo.reduce(into: [String?]()) { $0.append($1) }
        }
        #expect(llamadas.valor == 1)
        #expect(tokens.allSatisfy { $0 == "A2" })
    }

    @Test func siLaPeticionNoSalioElRefrescoVuelveAlCofre() async {
        let cofre = CofreMemoria(refresco: "R1")
        cofre.guardarCuenta(Data("cuenta".utf8))
        let termino = Contador()
        let gestor = GestorTokens(cofre: cofre, refrescar: { _ in .sinEnviar }, alTerminar: { termino.sumar() })
        #expect(await gestor.renovar(fallido: nil) == nil)
        #expect(cofre.leerRefresco() == "R1")
        #expect(cofre.leerCuenta() != nil, "se conserva la cuenta para abrir la app sin red")
        #expect(termino.valor == 0)
    }

    @Test(arguments: [Renovacion.rechazada, .incierta])
    func rechazadoOInciertoTerminaLaSesionYNuncaSeReenvia(_ resultado: Renovacion) async {
        let cofre = CofreMemoria(refresco: "R1")
        let termino = Contador()
        let gestor = GestorTokens(cofre: cofre, refrescar: { _ in resultado }, alTerminar: { termino.sumar() })
        #expect(await gestor.renovar(fallido: nil) == nil)
        #expect(cofre.leerRefresco() == nil)
        #expect(termino.valor == 1)
    }

    @Test func elRefrescadorDistingueUnaPeticionQueNoSalio() async {
        let t = TransporteFalso()
        let r = Refrescador(base: base, transporte: t)
        await t.encolar(.falla(.cannotConnectToHost), .falla(.timedOut), .http(401, "{}"), .http(200, sesionJSON("A", "R")))
        if case .sinEnviar = await r.refrescar("x") {} else { Issue.record("sin conexión al host: no salió") }
        if case .incierta = await r.refrescar("x") {} else { Issue.record("tiempo agotado: incierto") }
        if case .rechazada = await r.refrescar("x") {} else { Issue.record("401: rechazado") }
        if case .lista = await r.refrescar("x") {} else { Issue.record("200: lista") }
    }
}

@Suite("Cliente HTTP")
struct ClienteAPITests {
    let transporte = TransporteFalso()
    let cofre = CofreMemoria()

    func cliente() -> (ClienteAPI, GestorTokens) {
        let refrescador = Refrescador(base: base, transporte: transporte)
        let gestor = GestorTokens(cofre: cofre) { await refrescador.refrescar($0) }
        return (ClienteAPI(base: base, transporte: transporte, gestor: gestor), gestor)
    }

    @Test func agregaElJWTYUnCorrelationIdDistintoPorPeticion() async throws {
        let (api, gestor) = cliente()
        await gestor.guardar(sesionDTO("A1", "R1"))
        await transporte.encolar(.http(200, reporteJSON()), .http(200, reporteJSON()))
        _ = try await api.ejecutar(Peticion(metodo: "GET", ruta: "api/v1/reportes/r-1"))
        _ = try await api.ejecutar(Peticion(metodo: "GET", ruta: "api/v1/reportes/r-1"))
        let p = await transporte.peticiones
        #expect(p[0].cabecera("Authorization") == "Bearer A1")
        #expect(p[0].cabecera("X-Correlation-Id")?.count == 36)
        #expect(p[0].cabecera("X-Correlation-Id") != p[1].cabecera("X-Correlation-Id"))
    }

    @Test func anteUn401RenuevaUnaVezYRepiteConElMismoCorrelationId() async throws {
        let (api, gestor) = cliente()
        await gestor.guardar(sesionDTO("A1", "R1"))
        await transporte.encolar(.http(401, #"{"title":"No autenticado"}"#), .http(200, sesionJSON("A2", "R2")), .http(200, reporteJSON()))
        _ = try await api.ejecutar(Peticion(metodo: "GET", ruta: "api/v1/reportes/r-1"))
        let p = await transporte.peticiones
        #expect(p[1].url?.path == "/api/v1/auth/refrescar")
        #expect(p[1].cuerpoTexto.contains("R1"))
        #expect(p[1].cabecera("Authorization") == nil, "el refresco no lleva JWT")
        #expect(p[2].cabecera("Authorization") == "Bearer A2")
        #expect(p[2].cabecera("X-Correlation-Id") == p[0].cabecera("X-Correlation-Id"))
        #expect(cofre.leerRefresco() == "R2")
    }

    @Test func alAbrirLaAppSinTokenDeAccesoRenuevaAntesDeLaPrimeraLlamada() async throws {
        let (api, _) = cliente()
        cofre.guardarRefresco("R1")
        await transporte.encolar(.http(200, sesionJSON("A2", "R2")), .http(200, reporteJSON()))
        _ = try await api.ejecutar(Peticion(metodo: "GET", ruta: "api/v1/reportes/r-1"))
        let p = await transporte.peticiones
        #expect(p[0].url?.path == "/api/v1/auth/refrescar")
        #expect(p[1].cabecera("Authorization") == "Bearer A2")
    }

    @Test func traduceLosErroresProblemJson() async {
        let (api, _) = cliente()
        await transporte.encolar(.http(400, #"{"title":"Solicitud inválida","errores":[{"campo":"ubicacion","mensaje":"Fuera de Colombia"}]}"#),
                                 .http(503, #"{"title":"No disponible","correlationId":"abc"}"#), .falla(.notConnectedToInternet))
        await #expect(throws: ErrorApp(tipo: .validacion, mensaje: "Fuera de Colombia")) {
            try await api.ejecutar(Peticion(metodo: "GET", ruta: "x"))
        }
        do {
            _ = try await api.ejecutar(Peticion(metodo: "GET", ruta: "x"))
        } catch let e as ErrorApp {
            #expect(e.tipo == .servidor && e.mensaje.contains("referencia abc"))
        } catch { Issue.record("error inesperado") }
        await #expect(throws: ErrorApp.sinConexion) { try await api.ejecutar(Peticion(metodo: "GET", ruta: "x")) }
    }

    @Test func laDenunciaAnonimaNoEnviaJWTNiCorrelationIdAunqueHayaSesion() async throws {
        let (_, gestor) = cliente()
        await gestor.guardar(sesionDTO("A1", "R1"))
        let carpeta = CarpetaFotos(url: FileManager.default.temporaryDirectory)
        let nombre = "IMG_ana_perez_\(UUID().uuidString).jpg"
        try Data([0xFF, 0xD8, 0xFF]).write(to: carpeta.ruta(nombre))
        await transporte.encolar(.http(202, #"{"idDenuncia":"d","estado":"RECIBIDA"}"#))
        let repo = RepositorioDenunciasImpl(publica: ClienteAPI(base: base, transporte: transporte, gestor: nil), fotos: carpeta)
        try await repo.enviar(NuevaDenuncia(tipoServicio: .GAS, ubicacion: Ubicacion(latitud: 4.65, longitud: -74.05),
                                            descripcion: "Conexión ilegal al medidor"), foto: nombre)
        let p = try #require(await transporte.peticiones.first)
        #expect(p.cabecera("Authorization") == nil)
        #expect(p.cabecera("X-Correlation-Id") == nil)
        #expect(p.cabecera("Cookie") == nil)
        let cuerpo = String(decoding: p.httpBody ?? Data(), as: UTF8.self)
        #expect(cuerpo.contains("name=\"denuncia\""))
        #expect(cuerpo.contains("filename=\"foto.jpg\""))
        #expect(!cuerpo.contains("ana_perez"), "el nombre original no sale del teléfono")
        #expect(!FileManager.default.fileExists(atPath: carpeta.ruta(nombre).path), "la foto se borra al enviarla")
    }
}

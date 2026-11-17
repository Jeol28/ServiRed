import XCTest

/// Recorre todas las pantallas y guarda capturas en SERVIRED_CAPTURAS (solo si se define:
/// `TEST_RUNNER_SERVIRED_CAPTURAS=/ruta xcodebuild test ...`). Requiere el compose arriba.
final class RecorridoCapturasUITests: XCTestCase {
    private var carpeta: URL!

    override func setUpWithError() throws {
        continueAfterFailure = false
        let ruta = ProcessInfo.processInfo.environment["SERVIRED_CAPTURAS"]
        try XCTSkipIf(ruta == nil, "solo bajo demanda: defina TEST_RUNNER_SERVIRED_CAPTURAS")
        carpeta = URL(fileURLWithPath: ruta!)
    }

    @MainActor
    private func capturar(_ nombre: String, esperar: TimeInterval = 3) {
        Thread.sleep(forTimeInterval: esperar)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: carpeta.appending(path: "\(nombre).png"))
    }

    /// Crea un ciudadano con un reporte de acueducto por el gateway.
    @MainActor
    private func ciudadanoConReporte() throws -> (String, String) {
        let correo = "cap-\(UUID().uuidString.prefix(6).lowercased())@prueba.servired.co", clave = "una clave larga de prueba"
        func llamar(_ ruta: String, _ cuerpo: [String: Any], token: String? = nil) throws -> [String: Any] {
            var r = URLRequest(url: URL(string: "http://localhost:8080\(ruta)")!)
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
            r.httpBody = try JSONSerialization.data(withJSONObject: cuerpo)
            let fin = expectation(description: ruta)
            nonisolated(unsafe) var salida: [String: Any] = [:]
            URLSession.shared.dataTask(with: r) { d, _, _ in
                salida = (d.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]) ?? [:]
                fin.fulfill()
            }.resume()
            wait(for: [fin], timeout: 15)
            return salida
        }
        _ = try llamar("/api/v1/auth/registro", ["correo": correo, "clave": clave, "nombre": "Ana", "aceptaTratamientoDatos": true])
        let token = try XCTUnwrap(try llamar("/api/v1/auth/login", ["correo": correo, "clave": clave])["tokenAcceso"] as? String)
        let inicio = ISO8601DateFormatter().string(from: Date().addingTimeInterval(-3 * 3600))
        _ = try llamar("/api/v1/reportes", ["tipoServicio": "ACUEDUCTO", "ubicacion": ["latitud": 4.6287, "longitud": -74.0648],
                                           "inicioEstimado": inicio, "magnitud": "BARRIO", "descripcion": "Sin agua desde la mañana"], token: token)
        return (correo, clave)
    }

    @MainActor
    func testRecorrerPantallas() throws {
        let (correo, clave) = try ciudadanoConReporte()
        let app = XCUIApplication()
        app.launchArguments = ["--sesion-limpia"]
        app.launch()
        capturar("i1-ingreso")
        app.textFields["correo"].tap()
        app.textFields["correo"].typeText(correo)
        app.secureTextFields["clave"].tap()
        app.secureTextFields["clave"].typeText(clave)
        app.buttons["enviar"].tap()
        XCTAssertTrue(app.navigationBars["Mis reportes"].waitForExistence(timeout: 20))
        capturar("i2-mis-reportes", esperar: 4)

        app.buttons["reportar"].tap()
        capturar("i3-nuevo-reporte", esperar: 6)
        app.swipeUp()
        capturar("i3b-nuevo-reporte-abajo", esperar: 2)
        app.navigationBars.buttons.firstMatch.tap()

        app.tabBars.buttons["Cercanos"].tap()
        capturar("i4-cercanos", esperar: 7)
        app.tabBars.buttons["Denunciar"].tap()
        capturar("i5-denuncia", esperar: 5)
        app.tabBars.buttons["Cuenta"].tap()
        capturar("i7-cuenta")

        app.tabBars.buttons["Mis reportes"].tap()
        app.staticTexts["Acueducto"].firstMatch.tap()
        capturar("i6-detalle", esperar: 6)
    }
}

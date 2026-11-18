import XCTest

/// La app completa (Keychain, SwiftData, red real): sin cuenta abre en el mapa;
/// «Reportar» invita a registrarse, se crea la cuenta y se elimina desde el menú,
/// volviendo al mapa sin cuenta. Requiere el servidor arriba.
final class RegistroUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testRegistrarseYEliminarLaCuentaDesdeLaApp() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--sesion-limpia"]
        app.launch()

        XCTAssertTrue(app.buttons["ingresar"].waitForExistence(timeout: 15))
        app.buttons["reportar"].tap()
        let crear = app.buttons["crear-cuenta"]
        XCTAssertTrue(crear.waitForExistence(timeout: 5))
        crear.tap()
        let correo = app.textFields["correo"]
        XCTAssertTrue(correo.waitForExistence(timeout: 10))
        correo.tap()
        correo.typeText("ui-\(UUID().uuidString.prefix(8).lowercased())@prueba.servired.co")
        let clave = app.secureTextFields["clave"]
        clave.tap()
        clave.typeText("una clave larga de prueba")
        app.buttons["enviar"].tap()
        let aviso = app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'autorizar el tratamiento'")).firstMatch
        XCTAssertTrue(aviso.waitForExistence(timeout: 5), app.debugDescription)

        app.switches["acepta"].switches.firstMatch.tap()
        app.buttons["enviar"].tap()
        // De vuelta en el mapa, ahora con sesión: ya no está «Ingresar».
        XCTAssertTrue(app.buttons["reportar"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["ingresar"].waitForNonExistence(timeout: 10))

        app.buttons["menu"].tap()
        app.buttons["Mi cuenta"].tap()
        let eliminar = app.buttons["eliminar-cuenta"]
        XCTAssertTrue(eliminar.waitForExistence(timeout: 10))
        eliminar.tap()
        app.buttons["Eliminar"].firstMatch.tap()
        XCTAssertTrue(app.buttons["ingresar"].waitForExistence(timeout: 20))
    }
}

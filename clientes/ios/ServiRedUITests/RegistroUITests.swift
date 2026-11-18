import XCTest

/// La app completa (Keychain, SwiftData, red real): registrarse desde la pantalla,
/// llegar al mapa y eliminar la cuenta desde el menú. Requiere el servidor arriba.
final class RegistroUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testRegistrarseYEliminarLaCuentaDesdeLaApp() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--sesion-limpia"]
        app.launch()

        app.buttons["No tengo cuenta: registrarme"].tap()
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
        // La pantalla principal es el mapa, con el botón grande de reportar.
        XCTAssertTrue(app.buttons["reportar"].waitForExistence(timeout: 20))

        app.buttons["menu"].tap()
        app.buttons["Mi cuenta"].tap()
        let eliminar = app.buttons["eliminar-cuenta"]
        XCTAssertTrue(eliminar.waitForExistence(timeout: 10))
        eliminar.tap()
        app.buttons["Eliminar"].firstMatch.tap()
        XCTAssertTrue(app.buttons["No tengo cuenta: registrarme"].waitForExistence(timeout: 20))
    }
}

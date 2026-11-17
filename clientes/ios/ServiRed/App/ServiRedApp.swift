import SwiftUI

@main
struct ServiRedApp: App {
    init() {
        // Pruebas de interfaz: arrancar sin sesión ni datos guardados.
        if ProcessInfo.processInfo.arguments.contains("--sesion-limpia") {
            CofreKeychain().borrarTodo()
        }
    }

    var body: some Scene {
        WindowGroup {
            RaizApp(c: Contenedor.compartido)
        }
        .backgroundTask(.appRefresh(EnvioEnSegundoPlano.identificador)) {
            let reportes = await MainActor.run { Contenedor.compartido.reportes }
            if !(await reportes.enviarPendientes()) { EnvioEnSegundoPlano().programar() }
        }
    }
}

package co.edu.javeriana.servired.app

import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import org.junit.Assume.assumeTrue

/**
 * Las pruebas que crean cuentas, reportes o denuncias solo corren contra el compose local
 * (-PapiBase=http://localhost:8080/ con `adb reverse tcp:8080 tcp:8080`). La base por
 * defecto es el servidor en línea: ahí se saltan, salvo con el argumento de
 * instrumentación remoto=1 (-Pandroid.testInstrumentationRunnerArguments.remoto=1).
 */
fun soloContraElComposeLocal() {
    val host = URI(BuildConfig.API_BASE).host
    val remoto = InstrumentationRegistry.getArguments().getString("remoto") == "1"
    assumeTrue("Crea datos: solo contra el compose local", host in setOf("localhost", "127.0.0.1", "10.0.2.2") || remoto)
}

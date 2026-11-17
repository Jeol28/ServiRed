package co.edu.javeriana.servired.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.edu.javeriana.servired.app.ui.ActividadPrincipal
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La app completa (Keystore, Room, red real): registrarse desde la pantalla y llegar a «Mis reportes». */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PantallaIngresoTest {
    @get:Rule val regla = createAndroidComposeRule<ActividadPrincipal>()

    @Before fun sinSesion() = runBlocking {
        (regla.activity.application as ServiRedApp).contenedor.sesion.cerrar()
    }

    @Test fun registrarseDesdeLaApp() {
        regla.onNodeWithText("No tengo cuenta: registrarme").performScrollTo().performClick()
        regla.onNodeWithTag("correo").performTextInput("ui-${UUID.randomUUID().toString().take(8)}@prueba.servired.co")
        regla.onNodeWithTag("clave").performTextInput("una clave larga de prueba")
        regla.onNodeWithTag("enviar").performScrollTo().performClick()
        regla.onNodeWithText("Debe autorizar el tratamiento de sus datos para crear la cuenta.").performScrollTo().assertIsDisplayed()
        regla.onNodeWithTag("acepta").performScrollTo().performClick()
        regla.onNodeWithTag("enviar").performScrollTo().performClick()
        regla.waitUntil(30_000) {
            regla.onAllNodes(hasText("Reportar un corte"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        regla.onAllNodes(hasText("Mis reportes"), useUnmergedTree = true)[0].assertIsDisplayed()
        runBlocking { (regla.activity.application as ServiRedApp).contenedor.cuenta.eliminar() }
    }
}

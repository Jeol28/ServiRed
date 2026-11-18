package co.edu.javeriana.servired.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.edu.javeriana.servired.app.ui.ActividadPrincipal
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La app completa (Keystore, Room, red real): desde el mapa sin cuenta, registrarse y volver al mapa con sesión. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PantallaIngresoTest {
    @get:Rule val regla = createAndroidComposeRule<ActividadPrincipal>()

    companion object {
        /** Sin esto, el diálogo de permiso de ubicación tapa el mapa en una instalación nueva. */
        @BeforeClass @JvmStatic fun concederUbicacion() {
            val i = InstrumentationRegistry.getInstrumentation()
            listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION").forEach {
                i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, it)
            }
        }
    }

    @Before fun sinSesion() = runBlocking {
        (regla.activity.application as ServiRedApp).contenedor.sesion.cerrar()
    }

    @Test fun registrarseDesdeLaApp() {
        // Sin cuenta la app abre en el mapa; «Reportar» invita a crear la cuenta.
        regla.waitUntil(15_000) {
            regla.onAllNodes(hasTestTag("ingresar"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        regla.onNodeWithTag("reportar").performClick()
        regla.onNodeWithTag("crear-cuenta").performClick()
        regla.onNodeWithTag("correo").performTextInput("ui-${UUID.randomUUID().toString().take(8)}@prueba.servired.co")
        regla.onNodeWithTag("clave").performTextInput("una clave larga de prueba")
        regla.onNodeWithTag("enviar").performScrollTo().performClick()
        regla.onNodeWithText("Debe autorizar el tratamiento de sus datos para crear la cuenta.").performScrollTo().assertIsDisplayed()
        regla.onNodeWithTag("acepta").performScrollTo().performClick()
        regla.onNodeWithTag("enviar").performScrollTo().performClick()
        regla.waitUntil(30_000) {
            regla.onAllNodes(hasTestTag("reportar"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // De vuelta en el mapa, ahora con sesión: ya no está el botón «Ingresar».
        regla.waitUntil(15_000) {
            regla.onAllNodes(hasTestTag("ingresar"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        regla.onAllNodes(hasText("SERVI-RED"), useUnmergedTree = true)[0].assertIsDisplayed()
        runBlocking { (regla.activity.application as ServiRedApp).contenedor.cuenta.eliminar() }
    }
}

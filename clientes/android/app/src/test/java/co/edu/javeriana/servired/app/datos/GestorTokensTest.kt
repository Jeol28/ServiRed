package co.edu.javeriana.servired.app.datos

import co.edu.javeriana.servired.app.CofreMemoria
import co.edu.javeriana.servired.app.datos.remoto.ResumenCuentaDto
import co.edu.javeriana.servired.app.datos.remoto.SesionDto
import co.edu.javeriana.servired.app.datos.sesion.GestorTokens
import co.edu.javeriana.servired.app.datos.sesion.Renovacion
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestorTokensTest {
    private fun sesion(a: String, r: String) = SesionDto(a, "Bearer", 900, r, ResumenCuentaDto("c", "CIUDADANO"))

    @Test fun `el refresco se retira del cofre antes de usarlo y se guarda el nuevo`() {
        val cofre = CofreMemoria("R1")
        val gestor = GestorTokens(cofre)
        val token = gestor.renovar(null) { r ->
            assertEquals("R1", r)
            assertNull("ya no está en el cofre mientras se usa", cofre.refresco)
            Renovacion.Lista(sesion("A2", "R2"))
        }
        assertEquals("A2", token)
        assertEquals("R2", cofre.refresco)
    }

    @Test fun `renovaciones simultaneas usan el refresco una sola vez`() {
        val cofre = CofreMemoria("R1")
        val gestor = GestorTokens(cofre)
        val llamadas = AtomicInteger()
        val hilos = Executors.newFixedThreadPool(8)
        val listos = CountDownLatch(8)
        val tokens = java.util.concurrent.ConcurrentLinkedQueue<String?>()
        repeat(8) {
            hilos.execute {
                tokens += gestor.renovar("A1") {
                    llamadas.incrementAndGet()
                    Thread.sleep(50)
                    Renovacion.Lista(sesion("A2", "R2"))
                }
                listos.countDown()
            }
        }
        assertTrue(listos.await(5, TimeUnit.SECONDS))
        assertEquals(1, llamadas.get())
        assertTrue(tokens.all { it == "A2" })
    }

    @Test fun `si la peticion no salio, el refresco vuelve al cofre`() {
        val cofre = CofreMemoria("R1")
        var termino = false
        val gestor = GestorTokens(cofre, alTerminar = { termino = true })
        assertNull(gestor.renovar(null) { Renovacion.SinEnviar })
        assertEquals("R1", cofre.refresco)
        assertFalse(termino)
    }

    @Test fun `rechazado o incierto termina la sesion y nunca se reenvia`() {
        for (resultado in listOf(Renovacion.Rechazada, Renovacion.Incierta)) {
            val cofre = CofreMemoria("R1")
            var termino = false
            val gestor = GestorTokens(cofre, alTerminar = { termino = true })
            assertNull(gestor.renovar(null) { resultado })
            assertNull(cofre.refresco)
            assertTrue(termino)
            assertFalse(gestor.hayRefresco())
        }
    }
}

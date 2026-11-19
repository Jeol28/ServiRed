package co.edu.javeriana.servired.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.edu.javeriana.servired.app.datos.RepositorioCuentaImpl
import co.edu.javeriana.servired.app.datos.RepositorioDenunciasImpl
import co.edu.javeriana.servired.app.datos.RepositorioReportesImpl
import co.edu.javeriana.servired.app.datos.RepositorioSesionImpl
import co.edu.javeriana.servired.app.datos.local.BaseDatos
import co.edu.javeriana.servired.app.datos.remoto.crearApiCiudadano
import co.edu.javeriana.servired.app.datos.remoto.crearApiPublica
import co.edu.javeriana.servired.app.datos.remoto.jsonServiRed
import co.edu.javeriana.servired.app.datos.sesion.CofreTokens
import co.edu.javeriana.servired.app.dominio.EstadoReporte
import co.edu.javeriana.servired.app.dominio.EstadoSesion
import co.edu.javeriana.servired.app.dominio.Magnitud
import co.edu.javeriana.servired.app.dominio.NuevaDenuncia
import co.edu.javeriana.servired.app.dominio.NuevoReporte
import co.edu.javeriana.servired.app.dominio.ObjetoValidacion
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.Sentido
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.dominio.Ubicacion
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * De punta a punta contra el entorno de Docker Compose (gateway en el
 * anfitrión: 10.0.2.2:8080 desde el emulador). Usa las mismas piezas que la
 * app, con un cofre en memoria y Room en memoria.
 */
@RunWith(AndroidJUnit4::class)
class FlujoCiudadanoE2E {
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = BuildConfig.API_BASE

    private class CofreMemoria : CofreTokens {
        private var r: String? = null
        private var c: String? = null
        override fun leer() = r
        override fun guardar(refresco: String) { r = refresco }
        override fun borrarRefresco() { r = null }
        override fun borrarTodo() { r = null; c = null }
        override fun leerCuenta() = c
        override fun guardarCuenta(cuenta: String) { c = cuenta }
    }

    private inner class Ciudadano {
        val bd = Room.inMemoryDatabaseBuilder(contexto, BaseDatos::class.java).build()
        val publica = crearApiPublica(base)
        val sesion = RepositorioSesionImpl(publica, CofreMemoria(), jsonServiRed) { bd.reportes().borrarTodo() }
        val api = crearApiCiudadano(base, sesion.gestor, publica)
        val reportes = RepositorioReportesImpl(api, bd.reportes(), bd.pendientes(), jsonServiRed, {})
        val cuenta = RepositorioCuentaImpl(api, sesion)
        val correo = "android-${UUID.randomUUID().toString().take(8)}@prueba.servired.co"
    }

    private fun <T> exito(r: Resultado<T>): T {
        assertTrue("se esperaba éxito: $r", r is Resultado.Exito)
        return (r as Resultado.Exito).valor
    }

    @Test
    fun registroReporteRestablecimientoValidacionDenunciaYSupresion() = runBlocking {
        val ana = Ciudadano()
        exito(ana.sesion.registrar(ana.correo, "una clave larga de prueba", "Ana"))
        assertTrue(ana.sesion.estado.value is EstadoSesion.Autenticada)

        // Un punto distinto en cada corrida (dentro de Bogotá): «cercanos» ordena por distancia y
        // tiene un tope, y las corridas anteriores dejan muchos reportes en un punto fijo.
        val aqui = Ubicacion(4.6287 + kotlin.random.Random.nextDouble(-0.008, 0.008), -74.0648 + kotlin.random.Random.nextDouble(-0.008, 0.008))
        val nuevo = NuevoReporte(TipoServicio.ENERGIA, aqui, Instant.now().minus(Duration.ofHours(2)), Magnitud.CUADRA, "Sin luz (prueba Android)")
        assertEquals(true, exito(ana.reportes.crear(nuevo, emptyList())))
        exito(ana.reportes.actualizar())
        val reporte = ana.reportes.misReportes().first().single()
        assertEquals("11001", reporte.codigoMunicipio)
        assertEquals(EstadoReporte.RECIBIDO, reporte.estado)

        // Otro ciudadano cercano lo ve y confirma el corte.
        val beto = Ciudadano()
        exito(beto.sesion.registrar(beto.correo, "otra clave larga de prueba", null))
        val cerca = Ubicacion(aqui.latitud + 0.0003, aqui.longitud - 0.0002) // ~40 m
        assertTrue(exito(beto.reportes.cercanos(cerca)).any { it.id == reporte.id })
        val voto = exito(beto.reportes.validar(reporte.id, ObjetoValidacion.CORTE, Sentido.CONFIRMA, cerca))
        // Beto es una cuenta recién creada: su voto queda registrado, pero no suma al umbral (ADR-22).
        assertTrue(voto.nueva && voto.conteo.confirmaciones == 0)
        val repetido = exito(beto.reportes.validar(reporte.id, ObjetoValidacion.CORTE, Sentido.CONFIRMA, cerca))
        assertTrue("repetir el voto no lo cuenta dos veces", !repetido.nueva)
        val propio = ana.reportes.validar(reporte.id, ObjetoValidacion.CORTE, Sentido.CONFIRMA, aqui)
        assertEquals("No puede validar su propio reporte.", (propio as Resultado.Fallo).error.mensaje)

        val restablecido = exito(ana.reportes.restablecer(reporte.id, Instant.now().minus(Duration.ofMinutes(10)), "Volvió la luz"))
        assertEquals(EstadoReporte.RESTABLECIDO, restablecido.estado)
        assertTrue(restablecido.duracionMinutos!! in 105..115)

        // La vista nacional del mapa (zonas agregadas y anónimas).
        exito(ana.reportes.zonasPublicas())

        // Denuncia anónima por el cliente público.
        val denuncias = RepositorioDenunciasImpl(crearApiPublica(base), jsonServiRed)
        exito(denuncias.enviar(NuevaDenuncia(TipoServicio.GAS, aqui, "Conexión ilegal de gas (prueba Android)"), null))

        // Derecho de supresión: la sesión termina y los datos locales se borran.
        exito(ana.cuenta.eliminar())
        assertEquals(EstadoSesion.Anonima, ana.sesion.estado.value)
        assertTrue(ana.reportes.misReportes().first().isEmpty())
        exito(beto.cuenta.eliminar())
    }
}

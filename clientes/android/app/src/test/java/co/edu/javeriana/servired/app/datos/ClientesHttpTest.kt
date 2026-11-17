package co.edu.javeriana.servired.app.datos

import co.edu.javeriana.servired.app.CofreMemoria
import co.edu.javeriana.servired.app.datos.remoto.ApiCiudadano
import co.edu.javeriana.servired.app.datos.remoto.ApiPublica
import co.edu.javeriana.servired.app.datos.remoto.crearApiCiudadano
import co.edu.javeriana.servired.app.datos.remoto.crearApiPublica
import co.edu.javeriana.servired.app.datos.remoto.jsonServiRed
import co.edu.javeriana.servired.app.datos.sesion.GestorTokens
import co.edu.javeriana.servired.app.dominio.NuevaDenuncia
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.dominio.Ubicacion
import co.edu.javeriana.servired.app.reporteJson
import co.edu.javeriana.servired.app.sesionJson
import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClientesHttpTest {
    private val servidor = MockWebServer()
    private lateinit var cofre: CofreMemoria
    private lateinit var gestor: GestorTokens
    private lateinit var publica: ApiPublica
    private lateinit var ciudadano: ApiCiudadano

    @Before fun iniciar() {
        servidor.start()
        val base = servidor.url("/").toString()
        cofre = CofreMemoria()
        gestor = GestorTokens(cofre)
        publica = crearApiPublica(base)
        ciudadano = crearApiCiudadano(base, gestor, publica)
    }

    @After fun detener() = servidor.close()

    private fun responder(codigo: Int, cuerpo: String = "") =
        servidor.enqueue(MockResponse.Builder().code(codigo).addHeader("Content-Type", "application/json").body(cuerpo).build())

    @Test fun `agrega el JWT y un X-Correlation-Id distinto por peticion`() = runTest {
        gestor.guardar(jsonServiRed.decodeFromString(sesionJson("A1", "R1")))
        responder(200, reporteJson()); responder(200, reporteJson())
        ciudadano.reporte("r-1"); ciudadano.reporte("r-1")
        val a = servidor.takeRequest(); val b = servidor.takeRequest()
        assertEquals("Bearer A1", a.headers["Authorization"])
        assertTrue(a.headers["X-Correlation-Id"]!!.matches(Regex("[0-9a-f-]{36}")))
        assertTrue(a.headers["X-Correlation-Id"] != b.headers["X-Correlation-Id"])
    }

    @Test fun `ante un 401 renueva una vez y repite con el mismo correlationId`() = runTest {
        gestor.guardar(jsonServiRed.decodeFromString(sesionJson("A1", "R1")))
        responder(401, """{"title":"No autenticado"}""")
        responder(200, sesionJson("A2", "R2"))
        responder(200, reporteJson())
        ciudadano.reporte("r-1")
        val primera = servidor.takeRequest()
        val refresco = servidor.takeRequest()
        val segunda = servidor.takeRequest()
        assertEquals("/api/v1/auth/refrescar", refresco.url.encodedPath)
        assertTrue(refresco.body!!.utf8().contains("R1"))
        assertNull("el refresco no lleva JWT", refresco.headers["Authorization"])
        assertEquals("Bearer A2", segunda.headers["Authorization"])
        assertEquals(primera.headers["X-Correlation-Id"], segunda.headers["X-Correlation-Id"])
        assertEquals("R2", cofre.refresco)
    }

    @Test fun `al abrir la app sin token de acceso renueva antes de la primera llamada`() = runTest {
        cofre.refresco = "R1"
        responder(200, sesionJson("A2", "R2"))
        responder(200, reporteJson())
        ciudadano.reporte("r-1")
        assertEquals("/api/v1/auth/refrescar", servidor.takeRequest().url.encodedPath)
        assertEquals("Bearer A2", servidor.takeRequest().headers["Authorization"])
    }

    @Test fun `la denuncia anonima no envia JWT ni correlationId aunque haya sesion`() = runTest {
        gestor.guardar(jsonServiRed.decodeFromString(sesionJson("A1", "R1")))
        responder(202, """{"idDenuncia":"d","estado":"RECIBIDA","fechaRecepcion":"2026-11-17T12:00:00Z","tieneEvidencia":true}""")
        val foto = File.createTempFile("IMG_ana_perez", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, -1)) }
        val repo = RepositorioDenunciasImpl(publica, jsonServiRed)
        val r = repo.enviar(NuevaDenuncia(TipoServicio.GAS, Ubicacion(4.65, -74.05), "Conexión ilegal al medidor"), foto.path)
        assertTrue(r is Resultado.Exito)
        val req = servidor.takeRequest()
        assertNull(req.headers["Authorization"])
        assertNull(req.headers["X-Correlation-Id"])
        assertNull(req.headers["Cookie"])
        val cuerpo = req.body!!.utf8()
        assertTrue(cuerpo.contains("name=\"denuncia\""))
        assertTrue(cuerpo.contains("filename=\"foto.jpg\""))
        assertFalse("el nombre original no sale del teléfono", cuerpo.contains("ana_perez"))
        assertFalse("la foto se borra al enviarla", foto.exists())
    }
}

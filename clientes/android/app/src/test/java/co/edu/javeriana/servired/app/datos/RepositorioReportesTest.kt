package co.edu.javeriana.servired.app.datos

import co.edu.javeriana.servired.app.CofreMemoria
import co.edu.javeriana.servired.app.DaoPendientesMemoria
import co.edu.javeriana.servired.app.DaoReportesMemoria
import co.edu.javeriana.servired.app.datos.remoto.crearApiCiudadano
import co.edu.javeriana.servired.app.datos.remoto.crearApiPublica
import co.edu.javeriana.servired.app.datos.remoto.jsonServiRed
import co.edu.javeriana.servired.app.datos.sesion.GestorTokens
import co.edu.javeriana.servired.app.dominio.EstadoEnvio
import co.edu.javeriana.servired.app.dominio.Magnitud
import co.edu.javeriana.servired.app.dominio.NuevoReporte
import co.edu.javeriana.servired.app.dominio.ObjetoValidacion
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.Sentido
import co.edu.javeriana.servired.app.dominio.TipoError
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.dominio.Ubicacion
import co.edu.javeriana.servired.app.reporteJson
import co.edu.javeriana.servired.app.sesionJson
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepositorioReportesTest {
    private val servidor = MockWebServer()
    private val reloj = Clock.fixed(Instant.parse("2026-11-17T13:00:00Z"), ZoneOffset.UTC)
    private val daoPendientes = DaoPendientesMemoria()
    private val daoReportes = DaoReportesMemoria()
    private var programado = 0
    private lateinit var repo: RepositorioReportesImpl

    private val nuevo = NuevoReporte(
        TipoServicio.ENERGIA, Ubicacion(4.6287, -74.0648), Instant.parse("2026-11-17T12:00:00Z"), Magnitud.CUADRA, "Sin luz",
    )

    @Before fun iniciar() {
        servidor.start()
        val base = servidor.url("/").toString()
        val gestor = GestorTokens(CofreMemoria())
        gestor.guardar(jsonServiRed.decodeFromString(sesionJson("A1", "R1")))
        val api = crearApiCiudadano(base, gestor, crearApiPublica(base))
        repo = RepositorioReportesImpl(api, daoReportes, daoPendientes, jsonServiRed, { programado++ }, reloj)
    }

    @After fun detener() = servidor.close()

    private fun ok(codigo: Int, cuerpo: String) =
        servidor.enqueue(MockResponse.Builder().code(codigo).addHeader("Content-Type", "application/json").body(cuerpo).build())

    private fun caida() = servidor.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())

    @Test fun `con red el reporte se envia y queda en mis reportes`() = runTest {
        ok(201, reporteJson("r-1"))
        val r = repo.crear(nuevo, emptyList())
        assertEquals(Resultado.Exito(true), r)
        assertTrue(repo.pendientes().first().isEmpty())
        assertEquals("r-1", repo.misReportes().first().single().id)
        val cuerpo = servidor.takeRequest().body!!.utf8()
        assertTrue("la hora viaja con zona", Regex("\"inicioEstimado\":\"2026-11-17T\\d\\d:00(Z|[+-]\\d\\d:\\d\\d)\"").containsMatchIn(cuerpo))
    }

    @Test fun `sin red queda pendiente y el reintento usa el mismo correlationId`() = runTest {
        caida()
        assertEquals(Resultado.Exito(false), repo.crear(nuevo, emptyList()))
        assertEquals(1, programado)
        val pendiente = repo.pendientes().first().single()
        assertEquals(EstadoEnvio.PENDIENTE, pendiente.estado)
        // La petición cortada queda registrada sin cabeceras: se descarta.
        repeat(servidor.requestCount) { servidor.takeRequest() }

        ok(201, reporteJson("r-1"))
        assertTrue(repo.enviarPendientes())
        assertEquals(pendiente.correlationId, servidor.takeRequest().headers["X-Correlation-Id"])
        assertTrue(repo.pendientes().first().isEmpty())
    }

    @Test fun `un rechazo por datos invalidos queda fallido y no se reintenta`() = runTest {
        ok(400, """{"title":"Solicitud inválida","errores":[{"campo":"ubicacion","mensaje":"Fuera de Colombia"}]}""")
        assertEquals(Resultado.Exito(false).javaClass, repo.crear(nuevo, emptyList()).javaClass)
        val p = repo.pendientes().first().single()
        assertEquals(EstadoEnvio.FALLIDO, p.estado)
        assertEquals("Fuera de Colombia", p.error)
        assertTrue("no queda nada por reintentar", repo.enviarPendientes())
        repo.descartarPendiente(p.idLocal)
        assertTrue(repo.pendientes().first().isEmpty())
    }

    @Test fun `las fotos se suben despues del reporte con correlationId estables`() = runTest {
        val fotos = (0..1).map { File.createTempFile("foto", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, -1)) }.path }
        ok(201, reporteJson("r-1"))
        ok(201, reporteJson("r-1"))
        caida()
        assertEquals(Resultado.Exito(false), repo.crear(nuevo, fotos))
        val crear = servidor.takeRequest()
        val foto0 = servidor.takeRequest()
        val foto1 = servidor.takeRequest()
        assertEquals("/api/v1/reportes/r-1/evidencias", foto0.url.encodedPath)
        val cid = crear.headers["X-Correlation-Id"]!!
        assertEquals(RepositorioReportesImpl.correlacionFoto(cid, 0), foto0.headers["X-Correlation-Id"])

        ok(201, reporteJson("r-1"))
        assertTrue(repo.enviarPendientes())
        val reintento = servidor.takeRequest()
        assertEquals("solo se reintenta la foto que faltaba", "/api/v1/reportes/r-1/evidencias", reintento.url.encodedPath)
        assertEquals(foto1.headers["X-Correlation-Id"], reintento.headers["X-Correlation-Id"])
        assertTrue(fotos.none { File(it).exists() })
    }

    @Test fun `no se guarda un reporte con hora futura`() = runTest {
        val r = repo.crear(nuevo.copy(inicioEstimado = reloj.instant().plus(Duration.ofHours(1))), emptyList())
        assertTrue(r is Resultado.Fallo && r.error.tipo == TipoError.VALIDACION)
        assertTrue(repo.pendientes().first().isEmpty())
        assertEquals(0, servidor.requestCount)
    }

    @Test fun `validar traduce los motivos del servidor`() = runTest {
        servidor.enqueue(
            MockResponse.Builder().code(409).addHeader("Content-Type", "application/problem+json")
                .body("""{"title":"Conflicto","motivo":"LEJOS","detail":"distancia"}""").build(),
        )
        val r = repo.validar("r-9", ObjetoValidacion.CORTE, Sentido.CONFIRMA, Ubicacion(4.6, -74.0))
        assertEquals("Debe estar a menos de 1 km del reporte para validarlo.", (r as Resultado.Fallo).error.mensaje)
        ok(201, """{"idReporte":"r-9","objeto":"CORTE","confirmaciones":3,"controversias":0,"confirmacionesNetas":3,"validado":true}""")
        val v = repo.validar("r-9", ObjetoValidacion.CORTE, Sentido.CONFIRMA, Ubicacion(4.6, -74.0)) as Resultado.Exito
        assertTrue(v.valor.nueva && v.valor.conteo.validado)
    }
}

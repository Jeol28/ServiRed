package co.edu.javeriana.servired.app.ui

import co.edu.javeriana.servired.app.UbicacionFija
import co.edu.javeriana.servired.app.dominio.Conteo
import co.edu.javeriana.servired.app.dominio.EstadoReporte
import co.edu.javeriana.servired.app.dominio.EstadoSesion
import co.edu.javeriana.servired.app.dominio.Magnitud
import co.edu.javeriana.servired.app.dominio.NuevaDenuncia
import co.edu.javeriana.servired.app.dominio.NuevoReporte
import co.edu.javeriana.servired.app.dominio.ObjetoValidacion
import co.edu.javeriana.servired.app.dominio.Reporte
import co.edu.javeriana.servired.app.dominio.ReporteCercano
import co.edu.javeriana.servired.app.dominio.ReportePendiente
import co.edu.javeriana.servired.app.dominio.RepositorioDenuncias
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.RepositorioSesion
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.ResultadoValidacion
import co.edu.javeriana.servired.app.dominio.Sentido
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.dominio.Ubicacion
import co.edu.javeriana.servired.app.dominio.Zona
import co.edu.javeriana.servired.app.ui.denuncia.DenunciaViewModel
import co.edu.javeriana.servired.app.ui.ingreso.IngresoViewModel
import co.edu.javeriana.servired.app.ui.mapa.MapaViewModel
import co.edu.javeriana.servired.app.ui.reportes.NuevoReporteViewModel
import co.edu.javeriana.servired.app.ui.reportes.ResultadoEnvio
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    @Before fun antes() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun despues() = Dispatchers.resetMain()

    private val reloj = Clock.fixed(Instant.parse("2026-11-17T13:00:00Z"), ZoneOffset.UTC)

    private class SesionFalsa : RepositorioSesion {
        val llamadas = mutableListOf<String>()
        override val estado = MutableStateFlow<EstadoSesion>(EstadoSesion.Anonima)
        override suspend fun restaurar() = Unit
        override suspend fun ingresar(correo: String, clave: String): Resultado<Unit> { llamadas += "ingresar:$correo"; return Resultado.Exito(Unit) }
        override suspend fun registrar(correo: String, clave: String, nombre: String?): Resultado<Unit> { llamadas += "registrar:$correo"; return Resultado.Exito(Unit) }
        override suspend fun cerrar() = Unit
    }

    private open class ReportesFalso : RepositorioReportes {
        var creados = mutableListOf<NuevoReporte>()
        var enviar = true
        var cercanos: List<ReporteCercano> = emptyList()
        var mios = MutableStateFlow<List<Reporte>>(emptyList())
        val restablecidos = mutableListOf<String>()
        val votos = mutableListOf<Pair<String, Sentido>>()
        override fun misReportes(): Flow<List<Reporte>> = mios
        override fun pendientes(): Flow<List<ReportePendiente>> = flowOf(emptyList())
        override suspend fun actualizar() = Resultado.Exito(Unit)
        override suspend fun obtener(id: String): Resultado<Reporte> = TODO()
        override suspend fun crear(nuevo: NuevoReporte, fotos: List<String>): Resultado<Boolean> { creados += nuevo; return Resultado.Exito(enviar) }
        override suspend fun enviarPendientes() = true
        override suspend fun descartarPendiente(idLocal: Long) = Unit
        override suspend fun adjuntarFoto(idReporte: String, ruta: String): Resultado<Reporte> = TODO()
        override suspend fun foto(idReporte: String, idEvidencia: String): Resultado<ByteArray> = TODO()
        override suspend fun restablecer(id: String, fecha: Instant, comentario: String?): Resultado<Reporte> {
            restablecidos += id
            val r = mios.value.first { it.id == id }.copy(fechaRestablecimiento = fecha, estado = EstadoReporte.RESTABLECIDO)
            mios.value = mios.value.map { if (it.id == id) r else it }
            return Resultado.Exito(r)
        }
        override suspend fun cercanos(desde: Ubicacion) = Resultado.Exito(cercanos)
        override suspend fun zonasPublicas() = Resultado.Exito(listOf(Zona(Ubicacion(3.45, -76.53), 7)))
        override suspend fun validar(id: String, objeto: ObjetoValidacion, sentido: Sentido, desde: Ubicacion): Resultado<ResultadoValidacion> {
            votos += id to sentido
            return Resultado.Exito(ResultadoValidacion(objeto, Conteo(3, 0, 3), nueva = true))
        }
    }

    @Test fun `el registro exige un correo valido, una clave adecuada y la autorizacion de datos`() = runTest {
        val sesion = SesionFalsa()
        val vm = IngresoViewModel(sesion)
        vm.alternarModo()
        vm.cambiar { it.copy(correo = "ana@correo.co", clave = "una clave larga") }
        vm.enviar()
        assertTrue(vm.estado.value.error!!.contains("autorizar"))
        vm.cambiar { it.copy(aceptaTratamiento = true) }
        vm.enviar()
        assertEquals(listOf("registrar:ana@correo.co"), sesion.llamadas)
        assertEquals("", vm.estado.value.clave)
    }

    @Test fun `el reporte nuevo pide servicio, magnitud y ubicacion antes de enviar`() = runTest {
        val repo = ReportesFalso()
        val vm = NuevoReporteViewModel(repo, UbicacionFija(null), reloj = reloj)
        vm.enviar()
        assertEquals("Elija el servicio afectado.", vm.estado.value.error)
        vm.cambiar { it.copy(tipo = TipoServicio.GAS, magnitud = Magnitud.BARRIO) }
        vm.usarMiUbicacion()
        assertTrue(vm.estado.value.error!!.contains("mapa"))
        vm.cambiar { it.copy(ubicacion = Ubicacion(3.45, -76.53), minutosAtras = 90) }
        vm.enviar()
        assertEquals(Instant.parse("2026-11-17T11:30:00Z"), repo.creados.single().inicioEstimado)
        assertEquals(ResultadoEnvio.ENVIADO, vm.estado.value.resultado)
    }

    @Test fun `sin red el reporte queda pendiente`() = runTest {
        val repo = ReportesFalso().apply { enviar = false }
        val vm = NuevoReporteViewModel(repo, UbicacionFija(), reloj = reloj)
        vm.usarMiUbicacion()
        vm.cambiar { it.copy(tipo = TipoServicio.ENERGIA, magnitud = Magnitud.VIVIENDA) }
        vm.enviar()
        assertEquals(ResultadoEnvio.PENDIENTE, vm.estado.value.resultado)
    }

    @Test fun `maximo tres fotos y la que sobra se descarta`() = runTest {
        val descartadas = mutableListOf<String>()
        val vm = NuevoReporteViewModel(ReportesFalso(), UbicacionFija(), descartadas::add, reloj)
        (1..4).forEach { vm.agregarFoto("f$it") }
        assertEquals(listOf("f1", "f2", "f3"), vm.estado.value.fotos)
        assertEquals(listOf("f4"), descartadas)
        vm.quitarFoto("f2")
        assertEquals(listOf("f1", "f3"), vm.estado.value.fotos)
    }

    private val aqui = Ubicacion(4.6287, -74.0648)

    private fun cercano(id: String) = ReporteCercano(
        id, TipoServicio.ENERGIA, Magnitud.CUADRA, EstadoReporte.RECIBIDO, Instant.parse("2026-11-17T11:00:00Z"),
        Ubicacion(4.629, -74.065), 300, Conteo(1, 0, 1), null,
    )

    private fun propio(id: String) = Reporte(
        id, TipoServicio.ACUEDUCTO, aqui, "11001", "BOGOTÁ, D.C.", Instant.parse("2026-11-17T10:00:00Z"), Magnitud.BARRIO, null,
        EstadoReporte.RECIBIDO, Instant.parse("2026-11-17T10:05:00Z"), null, null, null, null, emptyList(), emptyList(),
    )

    @Test fun `el mapa carga ubicacion, cortes cercanos y zonas del pais`() = runTest {
        val repo = ReportesFalso().apply { cercanos = listOf(cercano("c1"), cercano("c2")) }
        val vm = MapaViewModel(repo, UbicacionFija(aqui), reloj)
        vm.refrescar()
        val e = vm.estado.value
        assertEquals(aqui, e.ubicacion)
        assertEquals(listOf("c1", "c2"), e.cercanos.map { it.id })
        assertEquals(7, e.zonas.single().reportes)
        assertTrue(!e.cargando)
    }

    @Test fun `sin cuenta el mapa muestra las zonas del pais pero no los cortes exactos`() = runTest {
        var actualizaciones = 0
        val repo = object : ReportesFalso() {
            override suspend fun actualizar(): Resultado.Exito<Unit> { actualizaciones++; return Resultado.Exito(Unit) }
        }.apply { cercanos = listOf(cercano("c1")) }
        val vm = MapaViewModel(repo, UbicacionFija(aqui), reloj, anonimo = true)
        vm.refrescar()
        val e = vm.estado.value
        assertEquals(aqui, e.ubicacion)
        assertEquals(7, e.zonas.single().reportes)
        assertTrue("los cortes exactos son solo para quien tiene cuenta (RNF-DAT2)", e.cercanos.isEmpty())
        assertEquals(0, actualizaciones)
    }

    @Test fun `sin ubicacion el mapa lo avisa y no busca cortes cercanos`() = runTest {
        val repo = ReportesFalso().apply { cercanos = listOf(cercano("c1")) }
        val vm = MapaViewModel(repo, UbicacionFija(null), reloj)
        vm.refrescar()
        assertTrue(vm.estado.value.sinUbicacion)
        assertTrue(vm.estado.value.cercanos.isEmpty())
    }

    @Test fun `confirmar un corte cercano lo cuenta y cierra la tarjeta`() = runTest {
        val repo = ReportesFalso().apply { cercanos = listOf(cercano("c1")) }
        val vm = MapaViewModel(repo, UbicacionFija(aqui), reloj)
        vm.refrescar()
        vm.seleccionar("c:c1")
        assertEquals("c1", vm.estado.value.cercanoSeleccionado?.id)
        vm.validar(Sentido.CONFIRMA)
        assertEquals(listOf("c1" to Sentido.CONFIRMA), repo.votos)
        val e = vm.estado.value
        assertNull(e.seleccion)
        assertTrue(e.cercanos.single().corte!!.validado)
        assertTrue(e.aviso!!.contains("Gracias"))
    }

    @Test fun `con un corte propio activo pregunta si ya volvio y responde en un toque`() = runTest {
        val repo = ReportesFalso().apply { mios.value = listOf(propio("r1"), propio("r2")) }
        val vm = MapaViewModel(repo, UbicacionFija(aqui), reloj)
        assertEquals("r1", vm.estado.value.preguntaVolvio?.id)
        vm.todaviaNo("r1")
        assertEquals("r2", vm.estado.value.preguntaVolvio?.id)
        vm.yaVolvio("r2")
        assertEquals(listOf("r2"), repo.restablecidos)
        assertNull("ya no hay cortes propios activos sin responder", vm.estado.value.preguntaVolvio)
        assertTrue(vm.estado.value.aviso!!.contains("volvió"))
    }

    @Test fun `seleccionar una marca propia oculta la pregunta y deseleccionar la vuelve a mostrar`() = runTest {
        val repo = ReportesFalso().apply { mios.value = listOf(propio("r1")) }
        val vm = MapaViewModel(repo, UbicacionFija(aqui), reloj)
        vm.seleccionar("m:r1")
        assertEquals("r1", vm.estado.value.propioSeleccionado?.id)
        assertNull(vm.estado.value.preguntaVolvio)
        vm.seleccionar(null)
        assertEquals("r1", vm.estado.value.preguntaVolvio?.id)
    }

    @Test fun `al enviar la denuncia se olvida su contenido`() = runTest {
        val enviadas = mutableListOf<NuevaDenuncia>()
        val repo = object : RepositorioDenuncias {
            override suspend fun enviar(denuncia: NuevaDenuncia, foto: String?): Resultado<Unit> { enviadas += denuncia; return Resultado.Exito(Unit) }
        }
        val vm = DenunciaViewModel(repo, UbicacionFija())
        vm.cambiar { it.copy(tipo = TipoServicio.ACUEDUCTO, descripcion = "corto") }
        vm.usarMiUbicacion()
        vm.enviar()
        assertTrue(vm.estado.value.error!!.contains("entre 10"))
        vm.cambiar { it.copy(descripcion = "Toma ilegal de agua en la esquina") }
        vm.enviar()
        assertEquals(1, enviadas.size)
        val e = vm.estado.value
        assertTrue(e.enviada)
        assertEquals("", e.descripcion)
        assertNull(e.ubicacion)
        assertNull(e.tipo)
    }
}

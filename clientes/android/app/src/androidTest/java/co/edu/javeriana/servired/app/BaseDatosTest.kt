package co.edu.javeriana.servired.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.edu.javeriana.servired.app.datos.local.BaseDatos
import co.edu.javeriana.servired.app.datos.local.PendienteEntidad
import co.edu.javeriana.servired.app.datos.local.ReporteEntidad
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaseDatosTest {
    private val bd = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BaseDatos::class.java).build()

    @After fun cerrar() = bd.close()

    @Test fun reportesOrdenadosYReemplazados() = runTest {
        val dao = bd.reportes()
        dao.guardar(listOf(ReporteEntidad("a", "{}", 1), ReporteEntidad("b", "{}", 2)))
        assertEquals(listOf("b", "a"), dao.observar().first().map { it.id })
        dao.reemplazar(listOf(ReporteEntidad("c", "{}", 3)))
        assertEquals(listOf("c"), dao.observar().first().map { it.id })
    }

    @Test fun pendientesPorEnviarExcluyeFallidos() = runTest {
        val dao = bd.pendientes()
        val id = dao.insertar(PendienteEntidad(correlationId = "x", json = "{}", fotos = "[]", estado = "PENDIENTE", creadoEn = 1))
        dao.insertar(PendienteEntidad(correlationId = "y", json = "{}", fotos = "[]", estado = "FALLIDO", creadoEn = 2))
        assertEquals(listOf("x"), dao.porEnviar().map { it.correlationId })
        dao.actualizar(dao.buscar(id)!!.copy(idReporte = "r-1", fotosEnviadas = 1))
        assertEquals("r-1", dao.buscar(id)!!.idReporte)
        dao.borrar(id)
        assertNull(dao.buscar(id))
        assertEquals(1, dao.observar().first().size)
    }
}

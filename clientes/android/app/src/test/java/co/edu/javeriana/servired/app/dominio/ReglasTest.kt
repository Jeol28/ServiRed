package co.edu.javeriana.servired.app.dominio

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReglasTest {
    private val ahora = Instant.parse("2026-11-17T12:00:00Z")
    private val bogota = Ubicacion(4.6287, -74.0648)
    private fun reporte(inicio: Instant = ahora, u: Ubicacion = bogota, d: String? = null) =
        NuevoReporte(TipoServicio.ENERGIA, u, inicio, Magnitud.CUADRA, d)

    @Test fun `un reporte valido no tiene errores`() {
        assertTrue(Reglas.validarReporte(reporte(ahora.minus(Duration.ofHours(2))), ahora).isEmpty())
    }

    @Test fun `la hora de inicio no puede ser futura salvo 5 minutos de tolerancia`() {
        assertTrue(Reglas.validarReporte(reporte(ahora.plus(Duration.ofMinutes(4))), ahora).isEmpty())
        assertEquals(listOf("La hora de inicio no puede ser futura."), Reglas.validarReporte(reporte(ahora.plus(Duration.ofMinutes(6))), ahora))
    }

    @Test fun `la hora de inicio no puede tener mas de 30 dias`() {
        assertEquals(1, Reglas.validarReporte(reporte(ahora.minus(Duration.ofDays(31))), ahora).size)
    }

    @Test fun `la ubicacion debe estar en Colombia`() {
        assertEquals(listOf("La ubicación debe estar en Colombia."), Reglas.validarReporte(reporte(u = Ubicacion(40.4, -3.7)), ahora))
    }

    @Test fun `la descripcion admite hasta 1000 caracteres`() {
        assertTrue(Reglas.validarReporte(reporte(d = "x".repeat(1000)), ahora).isEmpty())
        assertEquals(1, Reglas.validarReporte(reporte(d = "x".repeat(1001)), ahora).size)
    }

    @Test fun `el restablecimiento debe ser posterior al inicio y no futuro`() {
        val inicio = ahora.minus(Duration.ofHours(3))
        assertNull(Reglas.validarRestablecimiento(ahora.minus(Duration.ofHours(1)), inicio, ahora, null))
        assertTrue(Reglas.validarRestablecimiento(inicio, inicio, ahora, null)!!.contains("después"))
        assertTrue(Reglas.validarRestablecimiento(ahora.plus(Duration.ofHours(1)), inicio, ahora, null)!!.contains("futura"))
    }

    @Test fun `la denuncia exige entre 10 y 2000 caracteres sin contar espacios de los extremos`() {
        assertEquals(1, Reglas.validarDenuncia(NuevaDenuncia(TipoServicio.GAS, bogota, "   corto   ")).size)
        assertTrue(Reglas.validarDenuncia(NuevaDenuncia(TipoServicio.GAS, bogota, "Conexión ilegal al poste")).isEmpty())
    }

    @Test fun `la clave tiene entre 10 y 64 caracteres, 72 bytes y no es el correo`() {
        assertNull(Reglas.validarClave("una clave larga", "ana@correo.co"))
        assertTrue(Reglas.validarClave("corta", "ana@correo.co") != null)
        assertTrue(Reglas.validarClave("ñ".repeat(40), "ana@correo.co") != null) // 80 bytes
        assertTrue(Reglas.validarClave("Ana@Correo.co", "ana@correo.co") != null)
    }

    @Test fun `validado con 3 confirmaciones netas`() {
        assertTrue(Conteo(4, 1, 3).validado)
        assertTrue(!Conteo(3, 1, 2).validado)
    }
}

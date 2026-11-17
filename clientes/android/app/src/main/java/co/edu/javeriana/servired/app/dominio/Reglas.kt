package co.edu.javeriana.servired.app.dominio

import java.time.Duration
import java.time.Instant

/**
 * Reglas que la app comprueba antes de enviar, iguales a las del servidor
 * (que es quien decide). Así el ciudadano ve el error sin esperar la red y un
 * reporte guardado sin conexión no queda condenado a fallar.
 */
object Reglas {
    const val MAX_FOTOS = 3
    const val MAX_BYTES_FOTO = 5L * 1024 * 1024
    const val CONFIRMACIONES_PARA_VALIDAR = 3
    const val DISTANCIA_VALIDACION_METROS = 1000
    const val MAX_DESCRIPCION_REPORTE = 1000
    const val MAX_COMENTARIO_RESTABLECIMIENTO = 500
    val DENUNCIA_DESCRIPCION = 10..2000
    private val TOLERANCIA_RELOJ: Duration = Duration.ofMinutes(5)
    private val ANTIGUEDAD_MAXIMA: Duration = Duration.ofDays(30)
    private val LATITUD = -4.3..13.5
    private val LONGITUD = -82.0..-66.8
    private val CORREO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun enColombia(u: Ubicacion): Boolean = u.latitud in LATITUD && u.longitud in LONGITUD

    fun validarReporte(r: NuevoReporte, ahora: Instant): List<String> = buildList {
        if (!enColombia(r.ubicacion)) add("La ubicación debe estar en Colombia.")
        if (r.inicioEstimado.isAfter(ahora.plus(TOLERANCIA_RELOJ))) add("La hora de inicio no puede ser futura.")
        if (r.inicioEstimado.isBefore(ahora.minus(ANTIGUEDAD_MAXIMA))) {
            add("La hora de inicio no puede tener más de 30 días.")
        }
        if ((r.descripcion?.length ?: 0) > MAX_DESCRIPCION_REPORTE) {
            add("La descripción admite hasta $MAX_DESCRIPCION_REPORTE caracteres.")
        }
    }

    fun validarRestablecimiento(fecha: Instant, inicio: Instant, ahora: Instant, comentario: String?): String? = when {
        !fecha.isAfter(inicio) -> "El servicio volvió después de que empezó el corte: revise la hora."
        fecha.isAfter(ahora.plus(TOLERANCIA_RELOJ)) -> "La hora de restablecimiento no puede ser futura."
        (comentario?.length ?: 0) > MAX_COMENTARIO_RESTABLECIMIENTO ->
            "El comentario admite hasta $MAX_COMENTARIO_RESTABLECIMIENTO caracteres."
        else -> null
    }

    fun validarDenuncia(d: NuevaDenuncia): List<String> = buildList {
        if (!enColombia(d.ubicacion)) add("La ubicación debe estar en Colombia.")
        if (d.descripcion.trim().length !in DENUNCIA_DESCRIPCION) {
            add("La descripción debe tener entre ${DENUNCIA_DESCRIPCION.first} y ${DENUNCIA_DESCRIPCION.last} caracteres.")
        }
    }

    fun validarCorreo(correo: String): String? =
        if (CORREO.matches(correo.trim()) && correo.length <= 254) null else "Escriba un correo válido."

    /** Entre 10 y 64 caracteres, máximo 72 bytes (bcrypt) y distinta del correo. */
    fun validarClave(clave: String, correo: String): String? = when {
        clave.length < 10 || clave.length > 64 -> "La clave debe tener entre 10 y 64 caracteres."
        clave.toByteArray(Charsets.UTF_8).size > 72 -> "La clave es demasiado larga."
        clave.equals(correo.trim(), ignoreCase = true) -> "La clave no puede ser igual al correo."
        else -> null
    }
}

package co.edu.javeriana.servired.app.datos.sesion

import co.edu.javeriana.servired.app.datos.remoto.SesionDto

/** Almacén seguro del token de refresco y del resumen de la cuenta (cifrados en el teléfono). */
interface CofreTokens {
    fun leer(): String?
    fun guardar(refresco: String)
    /** Retira solo el refresco (antes de usarlo); el resumen de la cuenta se conserva. */
    fun borrarRefresco()
    /** Termina la sesión local: refresco y resumen de la cuenta. */
    fun borrarTodo()
    fun leerCuenta(): String?
    fun guardarCuenta(cuenta: String)
}

/** Resultado de intentar renovar la sesión con el token de refresco. */
sealed interface Renovacion {
    data class Lista(val sesion: SesionDto) : Renovacion
    /** El servidor rechazó el refresco (vencido, revocado o reutilizado). */
    data object Rechazada : Renovacion
    /** La petición no salió del teléfono: el refresco sigue siendo válido. */
    data object SinEnviar : Renovacion
    /** Falló después de enviarse: no se sabe si el servidor lo consumió. */
    data object Incierta : Renovacion
}

/**
 * Tokens de la sesión (ADR-17, versión móvil):
 * - El JWT de acceso (15 min) vive solo en memoria.
 * - El refresco (14 días, un solo uso) se guarda cifrado con una clave del
 *   Android Keystore. Se retira del cofre ANTES de usarlo: Identidad trata su
 *   reutilización como robo y revoca todas las sesiones, así que nunca se
 *   envía dos veces. Solo se devuelve si la petición no llegó a salir.
 * - Las renovaciones simultáneas se serializan: la segunda reutiliza el
 *   token que obtuvo la primera.
 */
class GestorTokens(
    private val cofre: CofreTokens,
    private val alGuardar: (SesionDto) -> Unit = {},
    private val alTerminar: () -> Unit = {},
) {
    @Volatile
    var acceso: String? = null
        private set

    private val candado = Any()

    fun hayRefresco(): Boolean = cofre.leer() != null

    fun guardar(sesion: SesionDto) {
        synchronized(candado) {
            acceso = sesion.tokenAcceso
            cofre.guardar(sesion.tokenRefresco)
        }
        alGuardar(sesion)
    }

    /**
     * Devuelve un token de acceso válido distinto de `fallido`, renovando si
     * hace falta, o null si la sesión terminó.
     */
    fun renovar(fallido: String?, refrescar: (String) -> Renovacion): String? = synchronized(candado) {
        acceso?.let { if (it != fallido) return it }
        val refresco = cofre.leer() ?: return null
        cofre.borrarRefresco()
        when (val r = refrescar(refresco)) {
            is Renovacion.Lista -> {
                acceso = r.sesion.tokenAcceso
                cofre.guardar(r.sesion.tokenRefresco)
                alGuardar(r.sesion)
                r.sesion.tokenAcceso
            }
            Renovacion.SinEnviar -> {
                cofre.guardar(refresco)
                null
            }
            Renovacion.Rechazada, Renovacion.Incierta -> {
                acceso = null
                cofre.borrarTodo()
                alTerminar()
                null
            }
        }
    }

    /** Termina la sesión local y devuelve el refresco (para revocarlo en el servidor). */
    fun limpiar(): String? = synchronized(candado) {
        val refresco = cofre.leer()
        acceso = null
        cofre.borrarTodo()
        refresco
    }
}

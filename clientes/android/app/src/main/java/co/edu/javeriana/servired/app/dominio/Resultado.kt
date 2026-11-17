package co.edu.javeriana.servired.app.dominio

enum class TipoError { SIN_CONEXION, SESION, VALIDACION, CONFLICTO, NO_ENCONTRADO, PROHIBIDO, LIMITE, SERVIDOR }

/** Error de una operación, ya traducido a un mensaje para el ciudadano. */
data class ErrorApp(val tipo: TipoError, val mensaje: String, val motivo: String? = null) {
    companion object {
        val sinConexion = ErrorApp(TipoError.SIN_CONEXION, "Sin conexión. Revise su red e intente de nuevo.")
        val sesionVencida = ErrorApp(TipoError.SESION, "Su sesión terminó. Ingrese de nuevo.")
        fun validacion(mensaje: String) = ErrorApp(TipoError.VALIDACION, mensaje)
    }
}

sealed interface Resultado<out T> {
    data class Exito<T>(val valor: T) : Resultado<T>
    data class Fallo(val error: ErrorApp) : Resultado<Nothing>
}

inline fun <T, R> Resultado<T>.map(f: (T) -> R): Resultado<R> = when (this) {
    is Resultado.Exito -> Resultado.Exito(f(valor))
    is Resultado.Fallo -> this
}

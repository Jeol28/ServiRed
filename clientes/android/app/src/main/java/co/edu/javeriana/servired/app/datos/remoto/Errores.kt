package co.edu.javeriana.servired.app.datos.remoto

import co.edu.javeriana.servired.app.dominio.ErrorApp
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.TipoError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

private val json = Json { ignoreUnknownKeys = true }

/** Ejecuta una llamada a la API y traduce cualquier falla a un ErrorApp. */
suspend fun <T> llamar(bloque: suspend () -> T): Resultado<T> = try {
    Resultado.Exito(bloque())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    Resultado.Fallo(errorHttp(e.code(), e.response()?.errorBody()?.string()))
} catch (e: IOException) {
    Resultado.Fallo(ErrorApp.sinConexion)
} catch (e: SerializationException) {
    Resultado.Fallo(ErrorApp(TipoError.SERVIDOR, "Respuesta inesperada del servidor."))
}

fun errorHttp(codigo: Int, cuerpo: String?): ErrorApp {
    val p = cuerpo?.let { runCatching { json.decodeFromString<ProblemaDto>(it) }.getOrNull() }
    val campos = p?.errores?.filter { it.mensaje.isNotBlank() }?.joinToString("\n") { it.mensaje }
    val texto = campos?.takeIf { it.isNotBlank() } ?: p?.detail ?: p?.title
    return when (codigo) {
        400, 422 -> ErrorApp(TipoError.VALIDACION, texto ?: "Revise los datos.", p?.motivo)
        401 -> ErrorApp.sesionVencida
        403 -> ErrorApp(TipoError.PROHIBIDO, texto ?: "No tiene permiso para esta acción.", p?.motivo)
        404 -> ErrorApp(TipoError.NO_ENCONTRADO, texto ?: "No se encontró.", p?.motivo)
        409 -> ErrorApp(TipoError.CONFLICTO, texto ?: "La operación no es posible ahora.", p?.motivo)
        413 -> ErrorApp(TipoError.VALIDACION, "La foto supera 5 MB.", p?.motivo)
        429 -> ErrorApp(TipoError.LIMITE, "Demasiados intentos. Espere un momento e intente de nuevo.", p?.motivo)
        else -> ErrorApp(
            TipoError.SERVIDOR,
            "El servicio no está disponible. Intente más tarde." + (p?.correlationId?.let { " (referencia $it)" } ?: ""),
            p?.motivo,
        )
    }
}

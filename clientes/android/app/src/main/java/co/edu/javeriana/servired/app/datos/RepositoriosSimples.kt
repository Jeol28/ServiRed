package co.edu.javeriana.servired.app.datos

import co.edu.javeriana.servired.app.datos.remoto.ApiCiudadano
import co.edu.javeriana.servired.app.datos.remoto.ApiPublica
import co.edu.javeriana.servired.app.datos.remoto.CambiosCuentaPropiaDto
import co.edu.javeriana.servired.app.datos.remoto.NuevaDenunciaDto
import co.edu.javeriana.servired.app.datos.remoto.dominio
import co.edu.javeriana.servired.app.datos.remoto.dto
import co.edu.javeriana.servired.app.datos.remoto.llamar
import co.edu.javeriana.servired.app.dominio.Cuenta
import co.edu.javeriana.servired.app.dominio.ErrorApp
import co.edu.javeriana.servired.app.dominio.NuevaDenuncia
import co.edu.javeriana.servired.app.dominio.Reglas
import co.edu.javeriana.servired.app.dominio.RepositorioCuenta
import co.edu.javeriana.servired.app.dominio.RepositorioDenuncias
import co.edu.javeriana.servired.app.dominio.RepositorioSesion
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.map
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * Denuncia anónima (RF-5, RNF-SEG4): usa la API pública, cuyo cliente no
 * agrega JWT ni correlationId. La foto va con un nombre neutro, ya sin EXIF,
 * y se borra del teléfono al enviarla. No se guarda ningún rastro local.
 */
class RepositorioDenunciasImpl(private val api: ApiPublica, private val json: Json) : RepositorioDenuncias {
    override suspend fun enviar(denuncia: NuevaDenuncia, foto: String?): Resultado<Unit> {
        Reglas.validarDenuncia(denuncia).firstOrNull()?.let { return Resultado.Fallo(ErrorApp.validacion(it)) }
        val dto = denuncia.dto()
        return llamar {
            val r = if (foto == null) {
                api.denunciar(dto)
            } else {
                val parteDenuncia = MultipartBody.Part.createFormData(
                    "denuncia", null,
                    json.encodeToString(NuevaDenunciaDto.serializer(), dto).toRequestBody("application/json".toMediaType()),
                )
                val parteFoto = MultipartBody.Part.createFormData(
                    "foto", "foto.jpg", File(foto).asRequestBody("image/jpeg".toMediaType()),
                )
                api.denunciarConFoto(parteDenuncia, parteFoto)
            }
            r.body()?.close()
            if (!r.isSuccessful) throw HttpException(r)
        }.also { foto?.let { File(it).delete() } }
    }
}

class RepositorioCuentaImpl(
    private val api: ApiCiudadano,
    private val sesion: RepositorioSesion,
) : RepositorioCuenta {
    override suspend fun yo(): Resultado<Cuenta> = llamar { api.yo() }.map { it.dominio() }

    override suspend fun cambiarNombre(nombre: String): Resultado<Cuenta> {
        if (nombre.trim().length > 120) return Resultado.Fallo(ErrorApp.validacion("El nombre admite hasta 120 caracteres."))
        return llamar { api.cambiarYo(CambiosCuentaPropiaDto(nombre = nombre.trim())) }.map { it.dominio() }
    }

    /** Cambiar la clave cierra todas las sesiones en el servidor: se termina también la local. */
    override suspend fun cambiarClave(actual: String, nueva: String): Resultado<Unit> {
        val correo = (yo() as? Resultado.Exito)?.valor?.correo ?: ""
        Reglas.validarClave(nueva, correo)?.let { return Resultado.Fallo(ErrorApp.validacion(it)) }
        return llamar { api.cambiarYo(CambiosCuentaPropiaDto(claveActual = actual, claveNueva = nueva)) }
            .map { }
            .also { if (it is Resultado.Exito) sesion.cerrar() }
    }

    /** Derecho de supresión: el servidor anonimiza la cuenta; aquí se borra todo lo local. */
    override suspend fun eliminar(): Resultado<Unit> = llamar {
        val r = api.eliminarYo()
        if (!r.isSuccessful) throw HttpException(r)
    }.also { if (it is Resultado.Exito) sesion.cerrar() }
}

package co.edu.javeriana.servired.app.datos

import co.edu.javeriana.servired.app.datos.remoto.ApiPublica
import co.edu.javeriana.servired.app.datos.remoto.CredencialesDto
import co.edu.javeriana.servired.app.datos.remoto.NuevoCiudadanoDto
import co.edu.javeriana.servired.app.datos.remoto.ResumenCuentaDto
import co.edu.javeriana.servired.app.datos.remoto.SesionDto
import co.edu.javeriana.servired.app.datos.remoto.SolicitudRefrescoDto
import co.edu.javeriana.servired.app.datos.remoto.llamar
import co.edu.javeriana.servired.app.datos.sesion.CofreTokens
import co.edu.javeriana.servired.app.datos.sesion.GestorTokens
import co.edu.javeriana.servired.app.dominio.ErrorApp
import co.edu.javeriana.servired.app.dominio.EstadoSesion
import co.edu.javeriana.servired.app.dominio.RepositorioSesion
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.TipoError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Sesión del ciudadano. El resumen de la cuenta se guarda cifrado junto al
 * refresco para abrir la app sin red (ver reportes guardados y dejar
 * reportes pendientes); el token de acceso se obtiene en la primera llamada.
 */
class RepositorioSesionImpl(
    private val api: ApiPublica,
    private val cofre: CofreTokens,
    private val json: Json,
    /** Borra los datos personales locales (reportes guardados y pendientes). */
    private val alCerrar: suspend () -> Unit,
) : RepositorioSesion {

    private val _estado = MutableStateFlow<EstadoSesion>(EstadoSesion.Desconocida)
    override val estado: StateFlow<EstadoSesion> = _estado

    val gestor = GestorTokens(
        cofre,
        alGuardar = { s -> recordar(s) },
        alTerminar = { _estado.value = EstadoSesion.Anonima },
    )

    override suspend fun restaurar() {
        val cuenta = cofre.leerCuenta()?.let { runCatching { json.decodeFromString<ResumenCuentaDto>(it) }.getOrNull() }
        _estado.value = if (gestor.hayRefresco() && cuenta != null) cuenta.estado() else EstadoSesion.Anonima
    }

    override suspend fun ingresar(correo: String, clave: String): Resultado<Unit> =
        when (val r = llamar { api.ingresar(CredencialesDto(correo.trim(), clave)) }) {
            is Resultado.Exito -> abrir(r.valor)
            is Resultado.Fallo -> Resultado.Fallo(
                if (r.error.tipo == TipoError.SESION) ErrorApp(TipoError.SESION, "Correo o clave incorrectos.") else r.error,
            )
        }

    override suspend fun registrar(correo: String, clave: String, nombre: String?): Resultado<Unit> {
        val nuevo = NuevoCiudadanoDto(correo.trim(), clave, nombre?.trim()?.takeIf { it.isNotEmpty() }, aceptaTratamientoDatos = true)
        return when (val r = llamar { api.registrar(nuevo) }) {
            is Resultado.Exito -> ingresar(correo, clave)
            is Resultado.Fallo -> r
        }
    }

    override suspend fun cerrar() {
        val refresco = gestor.limpiar()
        _estado.value = EstadoSesion.Anonima
        alCerrar()
        // Revocar en el servidor es de mejor esfuerzo: localmente la sesión ya terminó.
        if (refresco != null) llamar { api.cerrarSesion(SolicitudRefrescoDto(refresco)) }
    }

    private fun abrir(sesion: SesionDto): Resultado<Unit> {
        if (sesion.cuenta.rol != "CIUDADANO") {
            gestor.limpiar()
            return Resultado.Fallo(ErrorApp(TipoError.PROHIBIDO, "Esta app es para ciudadanos. El personal usa el portal de operación."))
        }
        gestor.guardar(sesion)
        return Resultado.Exito(Unit)
    }

    private fun recordar(s: SesionDto) {
        cofre.guardarCuenta(json.encodeToString(ResumenCuentaDto.serializer(), s.cuenta))
        _estado.value = s.cuenta.estado()
    }

    private fun ResumenCuentaDto.estado() = EstadoSesion.Autenticada(idCuenta, rol, nombre)
}

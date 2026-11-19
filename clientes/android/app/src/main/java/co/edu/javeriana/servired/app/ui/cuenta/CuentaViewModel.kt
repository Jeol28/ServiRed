package co.edu.javeriana.servired.app.ui.cuenta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.servired.app.dominio.Cuenta
import co.edu.javeriana.servired.app.dominio.EstadoEnvio
import co.edu.javeriana.servired.app.dominio.RepositorioCuenta
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.RepositorioSesion
import co.edu.javeriana.servired.app.dominio.Resultado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoCuenta(
    val cuenta: Cuenta? = null,
    val nombre: String = "",
    val claveActual: String = "",
    val claveNueva: String = "",
    val ocupado: Boolean = false,
    val error: String? = null,
    val aviso: String? = null,
    /** Mientras se intentan enviar los pendientes antes de cerrar sesión. */
    val enviandoPendientes: Boolean = false,
    /** Por qué no se cerró la sesión: quedan reportes sin enviar (se borrarían del teléfono). */
    val cierreBloqueado: String? = null,
)

class CuentaViewModel(
    private val repo: RepositorioCuenta,
    private val sesion: RepositorioSesion,
    private val reportes: RepositorioReportes,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoCuenta())
    val estado: StateFlow<EstadoCuenta> = _estado

    init {
        viewModelScope.launch {
            when (val r = repo.yo()) {
                is Resultado.Exito -> _estado.update { it.copy(cuenta = r.valor, nombre = r.valor.nombre.orEmpty()) }
                is Resultado.Fallo -> _estado.update { it.copy(error = r.error.mensaje) }
            }
        }
    }

    fun cambiar(f: (EstadoCuenta) -> EstadoCuenta) = _estado.update { f(it).copy(error = null, aviso = null) }

    fun guardarNombre() = ejecutar {
        when (val r = repo.cambiarNombre(_estado.value.nombre)) {
            is Resultado.Exito -> _estado.update { it.copy(cuenta = r.valor, aviso = "Nombre actualizado.") }
            is Resultado.Fallo -> _estado.update { it.copy(error = r.error.mensaje) }
        }
    }

    /** Si funciona, el servidor cierra todas las sesiones y la app vuelve al ingreso. */
    fun cambiarClave() = ejecutar {
        val e = _estado.value
        when (val r = repo.cambiarClave(e.claveActual, e.claveNueva)) {
            is Resultado.Exito -> Unit
            is Resultado.Fallo -> _estado.update { it.copy(error = r.error.mensaje) }
        }
    }

    fun eliminarCuenta() = ejecutar {
        (repo.eliminar() as? Resultado.Fallo)?.let { f -> _estado.update { it.copy(error = f.error.mensaje) } }
    }

    /**
     * Cerrar sesión borra del teléfono los reportes que no se han enviado. Por eso primero
     * se intentan enviar; si alguno queda (sin conexión o rechazado por el servidor), la
     * sesión no se cierra y se explica por qué. Para renunciar a un reporte, el ciudadano lo
     * descarta en Mis reportes: nunca se borra uno sin que lo decida.
     */
    fun cerrarSesion() = ejecutar {
        if (reportes.pendientes().first().isNotEmpty()) {
            _estado.update { it.copy(enviandoPendientes = true) }
            reportes.enviarPendientes()
            _estado.update { it.copy(enviandoPendientes = false) }
        }
        val quedan = reportes.pendientes().first()
        if (quedan.isEmpty()) {
            sesion.cerrar()
        } else {
            _estado.update { it.copy(cierreBloqueado = motivoBloqueo(quedan.size, quedan.count { p -> p.estado == EstadoEnvio.FALLIDO })) }
        }
    }

    fun cerrarAvisoBloqueo() = _estado.update { it.copy(cierreBloqueado = null) }

    private fun motivoBloqueo(total: Int, rechazados: Int): String {
        val sinEnviar = total - rechazados
        val partes = buildList {
            if (sinEnviar == 1) add("1 aún no se pudo enviar (sin conexión o el servicio no respondió): inténtelo de nuevo con conexión")
            if (sinEnviar > 1) add("$sinEnviar aún no se pudieron enviar (sin conexión o el servicio no respondió): inténtelo de nuevo con conexión")
            if (rechazados == 1) add("1 fue rechazado por el servidor: revíselo en Mis reportes")
            if (rechazados > 1) add("$rechazados fueron rechazados por el servidor: revíselos en Mis reportes")
        }
        val inicio = if (total == 1) "Tiene 1 reporte sin enviar, que se borraría" else "Tiene $total reportes sin enviar, que se borrarían"
        return "$inicio del teléfono al cerrar sesión. ${partes.joinToString("; ").replaceFirstChar { it.uppercase() }}. " +
            "Si ya no quiere enviar alguno, descártelo en Mis reportes."
    }

    private fun ejecutar(bloque: suspend () -> Unit) {
        _estado.update { it.copy(ocupado = true, error = null, aviso = null) }
        viewModelScope.launch {
            bloque()
            _estado.update { it.copy(ocupado = false, claveActual = "", claveNueva = "") }
        }
    }
}

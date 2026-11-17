package co.edu.javeriana.servired.app.ui.cuenta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.servired.app.dominio.Cuenta
import co.edu.javeriana.servired.app.dominio.RepositorioCuenta
import co.edu.javeriana.servired.app.dominio.RepositorioSesion
import co.edu.javeriana.servired.app.dominio.Resultado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
)

class CuentaViewModel(private val repo: RepositorioCuenta, private val sesion: RepositorioSesion) : ViewModel() {
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

    fun cerrarSesion() = ejecutar { sesion.cerrar() }

    private fun ejecutar(bloque: suspend () -> Unit) {
        _estado.update { it.copy(ocupado = true, error = null, aviso = null) }
        viewModelScope.launch {
            bloque()
            _estado.update { it.copy(ocupado = false, claveActual = "", claveNueva = "") }
        }
    }
}

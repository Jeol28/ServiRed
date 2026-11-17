package co.edu.javeriana.servired.app.ui.ingreso

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.servired.app.dominio.Reglas
import co.edu.javeriana.servired.app.dominio.RepositorioSesion
import co.edu.javeriana.servired.app.dominio.Resultado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoIngreso(
    val registro: Boolean = false,
    val correo: String = "",
    val clave: String = "",
    val nombre: String = "",
    val aceptaTratamiento: Boolean = false,
    val cargando: Boolean = false,
    val error: String? = null,
)

/** Ingreso y registro del ciudadano. Al abrir la sesión, la navegación cambia sola. */
class IngresoViewModel(private val sesion: RepositorioSesion) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoIngreso())
    val estado: StateFlow<EstadoIngreso> = _estado

    fun cambiar(f: (EstadoIngreso) -> EstadoIngreso) = _estado.update { f(it).copy(error = null) }

    fun alternarModo() = _estado.update { it.copy(registro = !it.registro, error = null, clave = "") }

    fun enviar() {
        val e = _estado.value
        val problema = Reglas.validarCorreo(e.correo)
            ?: if (e.registro) {
                Reglas.validarClave(e.clave, e.correo)
                    ?: if (!e.aceptaTratamiento) "Debe autorizar el tratamiento de sus datos para crear la cuenta." else null
            } else if (e.clave.isBlank()) "Escriba su clave." else null
        if (problema != null) {
            _estado.update { it.copy(error = problema) }
            return
        }
        _estado.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            val r = if (e.registro) sesion.registrar(e.correo, e.clave, e.nombre) else sesion.ingresar(e.correo, e.clave)
            _estado.update {
                when (r) {
                    is Resultado.Exito -> it.copy(cargando = false, clave = "")
                    is Resultado.Fallo -> it.copy(cargando = false, error = r.error.mensaje)
                }
            }
        }
    }
}

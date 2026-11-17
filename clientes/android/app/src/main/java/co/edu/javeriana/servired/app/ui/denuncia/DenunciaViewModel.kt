package co.edu.javeriana.servired.app.ui.denuncia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.servired.app.dominio.NuevaDenuncia
import co.edu.javeriana.servired.app.dominio.ProveedorUbicacion
import co.edu.javeriana.servired.app.dominio.Reglas
import co.edu.javeriana.servired.app.dominio.RepositorioDenuncias
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.dominio.Ubicacion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoDenuncia(
    val tipo: TipoServicio? = null,
    val ubicacion: Ubicacion? = null,
    val descripcion: String = "",
    val foto: String? = null,
    val enviando: Boolean = false,
    val error: String? = null,
    val enviada: Boolean = false,
)

/** Denuncia anónima (RF-5): no requiere cuenta ni guarda nada en el teléfono. */
class DenunciaViewModel(
    private val repo: RepositorioDenuncias,
    private val ubicacion: ProveedorUbicacion,
    private val descartarFoto: (String) -> Unit = {},
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoDenuncia())
    val estado: StateFlow<EstadoDenuncia> = _estado

    fun cambiar(f: (EstadoDenuncia) -> EstadoDenuncia) = _estado.update { f(it).copy(error = null) }

    fun usarMiUbicacion() {
        viewModelScope.launch {
            val u = ubicacion.actual()
            _estado.update { if (u != null) it.copy(ubicacion = u) else it.copy(error = "No se pudo obtener la ubicación. Márquela en el mapa.") }
        }
    }

    fun ponerFoto(ruta: String?) = _estado.update {
        it.foto?.let(descartarFoto)
        it.copy(foto = ruta)
    }

    fun enviar() {
        val e = _estado.value
        val tipo = e.tipo ?: return falla("Elija el servicio.")
        val u = e.ubicacion ?: return falla("Indique dónde ocurre.")
        val denuncia = NuevaDenuncia(tipo, u, e.descripcion)
        Reglas.validarDenuncia(denuncia).firstOrNull()?.let { return falla(it) }
        _estado.update { it.copy(enviando = true, error = null) }
        viewModelScope.launch {
            val r = repo.enviar(denuncia, e.foto)
            _estado.update {
                when (r) {
                    // Se olvida todo el contenido: no queda nada que vincule la denuncia con el teléfono.
                    is Resultado.Exito -> EstadoDenuncia(enviada = true)
                    is Resultado.Fallo -> it.copy(enviando = false, error = r.error.mensaje)
                }
            }
        }
    }

    fun nueva() = _estado.update { EstadoDenuncia() }

    override fun onCleared() {
        _estado.value.foto?.let(descartarFoto)
    }

    private fun falla(m: String) = _estado.update { it.copy(error = m) }
}

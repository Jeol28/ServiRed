package co.edu.javeriana.servired.app.ui.reportes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.servired.app.dominio.Reporte
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.Resultado
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoDetalle(
    val reporte: Reporte? = null,
    val cargando: Boolean = true,
    val ocupado: Boolean = false,
    val error: String? = null,
    val aviso: String? = null,
    val fotos: Map<String, ByteArray> = emptyMap(),
    /** Hace cuánto volvió el servicio (formulario de restablecimiento). */
    val minutosRestablecido: Long = 0,
    val comentario: String = "",
)

/** Estado del reporte, historia, fotos y registro del restablecimiento (RF-3). */
class DetalleReporteViewModel(
    private val id: String,
    private val repo: RepositorioReportes,
    private val reloj: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoDetalle())
    val estado: StateFlow<EstadoDetalle> = _estado

    init {
        cargar()
    }

    fun cargar() {
        viewModelScope.launch {
            val r = repo.obtener(id)
            _estado.update {
                when (r) {
                    is Resultado.Exito -> it.copy(reporte = r.valor, cargando = false)
                    is Resultado.Fallo -> it.copy(cargando = false, error = r.error.mensaje)
                }
            }
        }
    }

    fun cambiar(f: (EstadoDetalle) -> EstadoDetalle) = _estado.update { f(it).copy(error = null, aviso = null) }

    fun verFoto(idEvidencia: String) {
        viewModelScope.launch {
            when (val r = repo.foto(id, idEvidencia)) {
                is Resultado.Exito -> _estado.update { it.copy(fotos = it.fotos + (idEvidencia to r.valor)) }
                is Resultado.Fallo -> _estado.update { it.copy(error = r.error.mensaje) }
            }
        }
    }

    fun restablecer() {
        val e = _estado.value
        val fecha = reloj.instant().minus(Duration.ofMinutes(e.minutosRestablecido))
        ejecutar("Registramos que el servicio volvió. ¡Gracias!") { repo.restablecer(id, fecha, e.comentario) }
    }

    fun adjuntar(ruta: String) = ejecutar("Foto agregada.") { repo.adjuntarFoto(id, ruta) }

    private fun ejecutar(exito: String, accion: suspend () -> Resultado<Reporte>) {
        _estado.update { it.copy(ocupado = true, error = null, aviso = null) }
        viewModelScope.launch {
            val r = accion()
            _estado.update {
                when (r) {
                    is Resultado.Exito -> it.copy(ocupado = false, reporte = r.valor, aviso = exito, comentario = "")
                    is Resultado.Fallo -> it.copy(ocupado = false, error = r.error.mensaje)
                }
            }
        }
    }
}

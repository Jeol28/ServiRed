package co.edu.javeriana.servired.app.ui.reportes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.servired.app.dominio.Reporte
import co.edu.javeriana.servired.app.dominio.ReportePendiente
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.TipoError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EstadoMisReportes(
    val reportes: List<Reporte> = emptyList(),
    val pendientes: List<ReportePendiente> = emptyList(),
    val actualizando: Boolean = false,
    /** Sin red se muestran los reportes guardados en el teléfono. */
    val sinConexion: Boolean = false,
    val error: String? = null,
)

class MisReportesViewModel(private val repo: RepositorioReportes) : ViewModel() {
    private val actualizando = MutableStateFlow(false)
    private val problema = MutableStateFlow<Pair<Boolean, String?>>(false to null)

    val estado: StateFlow<EstadoMisReportes> = combine(
        repo.misReportes(), repo.pendientes(), actualizando, problema,
    ) { reportes, pendientes, cargando, (sinRed, error) ->
        EstadoMisReportes(reportes, pendientes, cargando, sinRed, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EstadoMisReportes())

    init {
        actualizar()
    }

    fun actualizar() {
        if (actualizando.value) return
        actualizando.value = true
        viewModelScope.launch {
            repo.enviarPendientes()
            problema.value = when (val r = repo.actualizar()) {
                is Resultado.Exito -> false to null
                is Resultado.Fallo -> (r.error.tipo == TipoError.SIN_CONEXION) to
                    r.error.mensaje.takeIf { r.error.tipo != TipoError.SIN_CONEXION }
            }
            actualizando.value = false
        }
    }

    fun descartar(idLocal: Long) {
        viewModelScope.launch { repo.descartarPendiente(idLocal) }
    }
}

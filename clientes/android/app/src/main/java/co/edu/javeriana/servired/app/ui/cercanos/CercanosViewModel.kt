package co.edu.javeriana.servired.app.ui.cercanos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.servired.app.dominio.ObjetoValidacion
import co.edu.javeriana.servired.app.dominio.ProveedorUbicacion
import co.edu.javeriana.servired.app.dominio.ReporteCercano
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.Sentido
import co.edu.javeriana.servired.app.dominio.Ubicacion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoCercanos(
    val ubicacion: Ubicacion? = null,
    val reportes: List<ReporteCercano> = emptyList(),
    val cargando: Boolean = false,
    val votando: String? = null,
    val error: String? = null,
    val aviso: String? = null,
)

/** Reportes de otros ciudadanos a menos de 1 km para confirmarlos o controvertirlos (RF-4). */
class CercanosViewModel(
    private val repo: RepositorioReportes,
    private val ubicacion: ProveedorUbicacion,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoCercanos())
    val estado: StateFlow<EstadoCercanos> = _estado

    fun buscar() {
        _estado.update { it.copy(cargando = true, error = null, aviso = null) }
        viewModelScope.launch {
            val u = ubicacion.actual()
            if (u == null) {
                _estado.update { it.copy(cargando = false, error = "Active la ubicación para ver los reportes cercanos.") }
                return@launch
            }
            val r = repo.cercanos(u)
            _estado.update {
                when (r) {
                    is Resultado.Exito -> it.copy(cargando = false, ubicacion = u, reportes = r.valor)
                    is Resultado.Fallo -> it.copy(cargando = false, ubicacion = u, error = r.error.mensaje)
                }
            }
        }
    }

    fun validar(id: String, objeto: ObjetoValidacion, sentido: Sentido) {
        val u = _estado.value.ubicacion ?: return
        _estado.update { it.copy(votando = id, error = null, aviso = null) }
        viewModelScope.launch {
            val r = repo.validar(id, objeto, sentido, u)
            _estado.update { e ->
                when (r) {
                    is Resultado.Exito -> e.copy(
                        votando = null,
                        reportes = e.reportes.map { c ->
                            if (c.id != id) c
                            else if (objeto == ObjetoValidacion.CORTE) c.copy(corte = r.valor.conteo)
                            else c.copy(restablecimiento = r.valor.conteo)
                        },
                        aviso = if (r.valor.nueva) "Gracias: su validación quedó registrada." else "Ya había validado este reporte.",
                    )
                    is Resultado.Fallo -> e.copy(votando = null, error = r.error.mensaje)
                }
            }
        }
    }
}

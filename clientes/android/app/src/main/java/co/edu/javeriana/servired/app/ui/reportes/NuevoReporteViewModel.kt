package co.edu.javeriana.servired.app.ui.reportes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.servired.app.dominio.Magnitud
import co.edu.javeriana.servired.app.dominio.NuevoReporte
import co.edu.javeriana.servired.app.dominio.ProveedorUbicacion
import co.edu.javeriana.servired.app.dominio.Reglas
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.dominio.Ubicacion
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ResultadoEnvio { ENVIADO, PENDIENTE }

data class EstadoNuevoReporte(
    val tipo: TipoServicio? = null,
    val magnitud: Magnitud? = null,
    /** Hace cuánto empezó el corte. */
    val minutosAtras: Long = 0,
    val ubicacion: Ubicacion? = null,
    val buscandoUbicacion: Boolean = false,
    val descripcion: String = "",
    val fotos: List<String> = emptyList(),
    val enviando: Boolean = false,
    val error: String? = null,
    val resultado: ResultadoEnvio? = null,
)

/** Formulario de un reporte nuevo (RF-1, RF-2). */
class NuevoReporteViewModel(
    private val repo: RepositorioReportes,
    private val ubicacion: ProveedorUbicacion,
    private val descartarFoto: (String) -> Unit = {},
    private val reloj: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoNuevoReporte())
    val estado: StateFlow<EstadoNuevoReporte> = _estado

    fun cambiar(f: (EstadoNuevoReporte) -> EstadoNuevoReporte) = _estado.update { f(it).copy(error = null) }

    fun usarMiUbicacion() {
        _estado.update { it.copy(buscandoUbicacion = true) }
        viewModelScope.launch {
            val u = ubicacion.actual()
            _estado.update {
                it.copy(
                    buscandoUbicacion = false,
                    ubicacion = u ?: it.ubicacion,
                    error = if (u == null && it.ubicacion == null) "No se pudo obtener su ubicación. Márquela en el mapa." else null,
                )
            }
        }
    }

    fun agregarFoto(ruta: String) = _estado.update {
        if (it.fotos.size >= Reglas.MAX_FOTOS) {
            descartarFoto(ruta)
            it.copy(error = "Máximo ${Reglas.MAX_FOTOS} fotos.")
        } else {
            it.copy(fotos = it.fotos + ruta, error = null)
        }
    }

    fun quitarFoto(ruta: String) {
        descartarFoto(ruta)
        _estado.update { it.copy(fotos = it.fotos - ruta) }
    }

    fun enviar() {
        val e = _estado.value
        val tipo = e.tipo ?: return falla("Elija el servicio afectado.")
        val magnitud = e.magnitud ?: return falla("Indique a cuántos afecta el corte.")
        val ubicacion = e.ubicacion ?: return falla("Indique dónde ocurre el corte.")
        val nuevo = NuevoReporte(
            tipoServicio = tipo,
            ubicacion = ubicacion,
            inicioEstimado = reloj.instant().minus(Duration.ofMinutes(e.minutosAtras)),
            magnitud = magnitud,
            descripcion = e.descripcion,
        )
        Reglas.validarReporte(nuevo, reloj.instant()).firstOrNull()?.let { return falla(it) }
        _estado.update { it.copy(enviando = true, error = null) }
        viewModelScope.launch {
            val r = repo.crear(nuevo, e.fotos)
            _estado.update {
                when (r) {
                    is Resultado.Exito -> it.copy(
                        enviando = false,
                        resultado = if (r.valor) ResultadoEnvio.ENVIADO else ResultadoEnvio.PENDIENTE,
                    )
                    is Resultado.Fallo -> it.copy(enviando = false, error = r.error.mensaje)
                }
            }
        }
    }

    private fun falla(mensaje: String) = _estado.update { it.copy(error = mensaje) }
}

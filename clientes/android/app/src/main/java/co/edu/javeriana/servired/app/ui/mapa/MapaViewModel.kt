package co.edu.javeriana.servired.app.ui.mapa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.servired.app.dominio.ObjetoValidacion
import co.edu.javeriana.servired.app.dominio.ProveedorUbicacion
import co.edu.javeriana.servired.app.dominio.Reporte
import co.edu.javeriana.servired.app.dominio.ReporteCercano
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.Sentido
import co.edu.javeriana.servired.app.dominio.TipoError
import co.edu.javeriana.servired.app.dominio.Ubicacion
import co.edu.javeriana.servired.app.dominio.Zona
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Marca seleccionada en el mapa: un corte cercano o un reporte propio. */
sealed interface Seleccion {
    data class Cercano(val id: String) : Seleccion
    data class Propio(val id: String) : Seleccion
}

data class EstadoMapa(
    val ubicacion: Ubicacion? = null,
    val cercanos: List<ReporteCercano> = emptyList(),
    val mios: List<Reporte> = emptyList(),
    val pendientes: Int = 0,
    val zonas: List<Zona> = emptyList(),
    val seleccion: Seleccion? = null,
    /** Reportes propios a los que ya se respondió "todavía no" en esta sesión. */
    val descartados: Set<String> = emptySet(),
    val cargando: Boolean = false,
    val ocupado: Boolean = false,
    val sinUbicacion: Boolean = false,
    val aviso: String? = null,
    val error: String? = null,
    /** Cambia para volver a centrar el mapa en el ciudadano. */
    val recentrar: Int = 0,
) {
    /** Reportes propios con el servicio todavía caído. */
    val misActivos: List<Reporte> get() = mios.filter { it.puedeRestablecer }

    val cercanoSeleccionado: ReporteCercano?
        get() = (seleccion as? Seleccion.Cercano)?.let { s -> cercanos.find { it.id == s.id } }

    val propioSeleccionado: Reporte?
        get() = (seleccion as? Seleccion.Propio)?.let { s -> mios.find { it.id == s.id } }

    /** Si nada está seleccionado, se pregunta por el primer corte propio activo: "¿Ya volvió la luz?". */
    val preguntaVolvio: Reporte?
        get() = if (seleccion == null) misActivos.firstOrNull { it.id !in descartados } else null
}

/**
 * Pantalla principal de la app: el mapa con los cortes cercanos (para
 * confirmarlos), los reportes propios y las zonas con cortes del país.
 */
class MapaViewModel(
    private val repo: RepositorioReportes,
    private val proveedor: ProveedorUbicacion,
    private val reloj: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoMapa())
    val estado: StateFlow<EstadoMapa> = _estado

    init {
        viewModelScope.launch { repo.misReportes().collect { l -> _estado.update { it.copy(mios = l) } } }
        viewModelScope.launch { repo.pendientes().collect { p -> _estado.update { it.copy(pendientes = p.size) } } }
    }

    /** Ubicación, cortes cercanos, reportes propios (y pendientes) y zonas nacionales. */
    fun refrescar() {
        if (_estado.value.cargando) return
        _estado.update { it.copy(cargando = true) }
        viewModelScope.launch {
            val u = proveedor.actual() ?: _estado.value.ubicacion
            _estado.update { it.copy(ubicacion = u, sinUbicacion = u == null) }
            if (u != null) {
                when (val r = repo.cercanos(u)) {
                    is Resultado.Exito -> _estado.update { it.copy(cercanos = r.valor, error = null) }
                    is Resultado.Fallo -> _estado.update { it.copy(error = mensaje(r.error.tipo, r.error.mensaje)) }
                }
            }
            repo.enviarPendientes()
            repo.actualizar()
            (repo.zonasPublicas() as? Resultado.Exito)?.let { r -> _estado.update { it.copy(zonas = r.valor) } }
            _estado.update { it.copy(cargando = false) }
        }
    }

    fun recentrar() {
        _estado.update { it.copy(recentrar = it.recentrar + 1) }
        refrescar()
    }

    /** `idMarca` es el id de la marca del mapa: "c:<id>" (cercano) o "m:<id>" (propio). */
    fun seleccionar(idMarca: String?) = _estado.update {
        it.copy(
            seleccion = when {
                idMarca == null -> null
                idMarca.startsWith("c:") -> Seleccion.Cercano(idMarca.removePrefix("c:"))
                idMarca.startsWith("m:") -> Seleccion.Propio(idMarca.removePrefix("m:"))
                else -> null
            },
            aviso = null,
            error = null,
        )
    }

    /** Confirmar ("también me afecta") o controvertir ("aquí no") el corte cercano seleccionado. */
    fun validar(sentido: Sentido, objeto: ObjetoValidacion = ObjetoValidacion.CORTE) {
        val c = _estado.value.cercanoSeleccionado ?: return
        val u = _estado.value.ubicacion ?: return
        _estado.update { it.copy(ocupado = true, error = null) }
        viewModelScope.launch {
            val r = repo.validar(c.id, objeto, sentido, u)
            _estado.update { e ->
                when (r) {
                    is Resultado.Exito -> e.copy(
                        ocupado = false,
                        seleccion = null,
                        cercanos = e.cercanos.map { x ->
                            when {
                                x.id != c.id -> x
                                objeto == ObjetoValidacion.CORTE -> x.copy(corte = r.valor.conteo)
                                else -> x.copy(restablecimiento = r.valor.conteo)
                            }
                        },
                        aviso = when {
                            !r.valor.nueva -> "Ya habías respondido por este corte."
                            sentido == Sentido.CONFIRMA -> "¡Gracias! Tu confirmación ayuda a priorizar este corte."
                            else -> "Gracias, lo tendremos en cuenta."
                        },
                    )
                    is Resultado.Fallo -> e.copy(ocupado = false, error = r.error.mensaje)
                }
            }
        }
    }

    /** Un toque: el servicio de un reporte propio volvió ahora. */
    fun yaVolvio(idReporte: String) {
        _estado.update { it.copy(ocupado = true, error = null) }
        viewModelScope.launch {
            val r = repo.restablecer(idReporte, reloj.instant(), null)
            _estado.update {
                when (r) {
                    is Resultado.Exito -> it.copy(ocupado = false, seleccion = null, aviso = "¡Qué bien! Registramos que el servicio volvió.")
                    is Resultado.Fallo -> it.copy(ocupado = false, error = r.error.mensaje)
                }
            }
        }
    }

    fun todaviaNo(idReporte: String) = _estado.update { it.copy(descartados = it.descartados + idReporte, seleccion = null) }

    fun avisar(texto: String) = _estado.update { it.copy(aviso = texto, error = null) }

    fun cerrarAviso() = _estado.update { it.copy(aviso = null, error = null) }

    private fun mensaje(tipo: TipoError, texto: String) =
        if (tipo == TipoError.SIN_CONEXION) "Sin conexión: mostramos lo último que tenemos." else texto
}

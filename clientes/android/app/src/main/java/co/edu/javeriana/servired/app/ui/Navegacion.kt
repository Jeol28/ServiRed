package co.edu.javeriana.servired.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import co.edu.javeriana.servired.app.Contenedor
import co.edu.javeriana.servired.app.dominio.EstadoSesion
import co.edu.javeriana.servired.app.ui.cercanos.CercanosViewModel
import co.edu.javeriana.servired.app.ui.cercanos.PantallaCercanos
import co.edu.javeriana.servired.app.ui.componentes.TemaServiRed
import co.edu.javeriana.servired.app.ui.cuenta.CuentaViewModel
import co.edu.javeriana.servired.app.ui.cuenta.PantallaCuenta
import co.edu.javeriana.servired.app.ui.denuncia.DenunciaViewModel
import co.edu.javeriana.servired.app.ui.denuncia.PantallaDenuncia
import co.edu.javeriana.servired.app.ui.ingreso.IngresoViewModel
import co.edu.javeriana.servired.app.ui.ingreso.PantallaIngreso
import co.edu.javeriana.servired.app.ui.reportes.DetalleReporteViewModel
import co.edu.javeriana.servired.app.ui.reportes.MisReportesViewModel
import co.edu.javeriana.servired.app.ui.reportes.NuevoReporteViewModel
import co.edu.javeriana.servired.app.ui.reportes.PantallaDetalleReporte
import co.edu.javeriana.servired.app.ui.reportes.PantallaMisReportes
import co.edu.javeriana.servired.app.ui.reportes.PantallaNuevoReporte
import kotlinx.serialization.Serializable

@Serializable object RutaIngreso
@Serializable object RutaDenuncia
@Serializable object RutaInicio
@Serializable object RutaNuevoReporte
@Serializable data class RutaDetalle(val id: String)

/**
 * Raíz de la app. Sin sesión: ingreso y denuncia anónima. Con sesión: las
 * pestañas del ciudadano. Al cambiar la sesión se descarta toda la pila.
 */
@Composable
fun AppServiRed(c: Contenedor) {
    val sesion by c.sesion.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.sesion.restaurar() }
    TemaServiRed {
        when (val s = sesion) {
            EstadoSesion.Desconocida -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            EstadoSesion.Anonima -> {
                val nav = rememberNavController()
                NavHost(nav, startDestination = RutaIngreso) {
                    composable<RutaIngreso> {
                        PantallaIngreso(viewModel { IngresoViewModel(c.sesion) }, alDenunciar = { nav.navigate(RutaDenuncia) })
                    }
                    composable<RutaDenuncia> {
                        PantallaDenuncia(
                            viewModel { DenunciaViewModel(c.denuncias, c.ubicacion, c.fotos::descartar) }, c.fotos,
                            enPestana = false, alVolver = { nav.popBackStack() },
                        )
                    }
                }
            }
            is EstadoSesion.Autenticada -> key(s.idCuenta) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = RutaInicio) {
                    composable<RutaInicio> {
                        Inicio(c, alNuevo = { nav.navigate(RutaNuevoReporte) }, alAbrir = { nav.navigate(RutaDetalle(it)) })
                    }
                    composable<RutaNuevoReporte> {
                        PantallaNuevoReporte(
                            viewModel { NuevoReporteViewModel(c.reportes, c.ubicacion, c.fotos::descartar) }, c.fotos,
                            alTerminar = { nav.popBackStack() },
                        )
                    }
                    composable<RutaDetalle> { entrada ->
                        val id = entrada.toRoute<RutaDetalle>().id
                        PantallaDetalleReporte(viewModel { DetalleReporteViewModel(id, c.reportes) }, c.fotos, alVolver = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}

private enum class Pestana(val titulo: String, val icono: ImageVector) {
    REPORTES("Mis reportes", Icons.Filled.Home),
    CERCANOS("Cercanos", Icons.Filled.LocationOn),
    DENUNCIA("Denunciar", Icons.Filled.Warning),
    CUENTA("Cuenta", Icons.Filled.Person),
}

@Composable
private fun Inicio(c: Contenedor, alNuevo: () -> Unit, alAbrir: (String) -> Unit) {
    var actual by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                Pestana.entries.forEachIndexed { i, p ->
                    NavigationBarItem(
                        selected = actual == i, onClick = { actual = i },
                        icon = { Icon(p.icono, contentDescription = null) }, label = { Text(p.titulo) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { margen ->
        Box(Modifier.padding(margen)) {
            when (Pestana.entries[actual]) {
                Pestana.REPORTES -> PantallaMisReportes(viewModel { MisReportesViewModel(c.reportes) }, alNuevo, alAbrir)
                Pestana.CERCANOS -> PantallaCercanos(viewModel { CercanosViewModel(c.reportes, c.ubicacion) })
                Pestana.DENUNCIA -> PantallaDenuncia(
                    viewModel { DenunciaViewModel(c.denuncias, c.ubicacion, c.fotos::descartar) }, c.fotos, enPestana = true,
                )
                Pestana.CUENTA -> PantallaCuenta(viewModel { CuentaViewModel(c.cuenta, c.sesion) })
            }
        }
    }
}

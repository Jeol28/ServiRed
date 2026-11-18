package co.edu.javeriana.servired.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import co.edu.javeriana.servired.app.Contenedor
import co.edu.javeriana.servired.app.dominio.EstadoSesion
import co.edu.javeriana.servired.app.ui.componentes.TemaServiRed
import co.edu.javeriana.servired.app.ui.cuenta.CuentaViewModel
import co.edu.javeriana.servired.app.ui.cuenta.PantallaCuenta
import co.edu.javeriana.servired.app.ui.denuncia.DenunciaViewModel
import co.edu.javeriana.servired.app.ui.denuncia.PantallaDenuncia
import co.edu.javeriana.servired.app.ui.ingreso.IngresoViewModel
import co.edu.javeriana.servired.app.ui.ingreso.PantallaIngreso
import co.edu.javeriana.servired.app.ui.mapa.MapaViewModel
import co.edu.javeriana.servired.app.ui.mapa.PantallaMapa
import co.edu.javeriana.servired.app.ui.reportes.DetalleReporteViewModel
import co.edu.javeriana.servired.app.ui.reportes.MisReportesViewModel
import co.edu.javeriana.servired.app.ui.reportes.PantallaDetalleReporte
import co.edu.javeriana.servired.app.ui.reportes.PantallaMisReportes
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object RutaIngreso
@Serializable object RutaDenuncia
@Serializable object RutaMapa
@Serializable object RutaMisReportes
@Serializable object RutaCuenta
@Serializable data class RutaDetalle(val id: String)

/**
 * Raíz de la app. Sin sesión: ingreso y denuncia anónima. Con sesión: el mapa
 * como pantalla principal y un menú lateral con lo demás. Al cambiar la sesión
 * se descarta toda la pila.
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
            is EstadoSesion.Autenticada -> key(s.idCuenta) { ConSesion(c, s.nombre) }
        }
    }
}

@Composable
private fun ConSesion(c: Contenedor, nombre: String?) {
    val nav = rememberNavController()
    val cajon = rememberDrawerState(DrawerValue.Closed)
    val alcance = rememberCoroutineScope()
    val actual by nav.currentBackStackEntryAsState()
    fun ir(ruta: Any) {
        alcance.launch { cajon.close() }
        if (ruta == RutaMapa) nav.popBackStack(RutaMapa, inclusive = false) else nav.navigate(ruta) { launchSingleTop = true }
    }
    ModalNavigationDrawer(
        drawerState = cajon,
        // El menú solo se abre con gestos desde el mapa: en las demás pantallas manda "Volver".
        gesturesEnabled = cajon.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(24.dp)) {
                    Text("SERVI-RED", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text(nombre?.let { "Hola, $it" } ?: "Reporte cortes de servicios públicos", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                ElementoMenu("Mapa", Icons.Filled.Home, actual.esRuta<RutaMapa>()) { ir(RutaMapa) }
                ElementoMenu("Mis reportes", Icons.AutoMirrored.Filled.List, actual.esRuta<RutaMisReportes>()) { ir(RutaMisReportes) }
                ElementoMenu("Denuncia anónima", Icons.Filled.Warning, actual.esRuta<RutaDenuncia>()) { ir(RutaDenuncia) }
                ElementoMenu("Mi cuenta", Icons.Filled.Person, actual.esRuta<RutaCuenta>()) { ir(RutaCuenta) }
            }
        },
    ) {
        Rutas(c, nav, alAbrirMenu = { alcance.launch { cajon.open() } })
    }
}

@Composable
private fun Rutas(c: Contenedor, nav: NavHostController, alAbrirMenu: () -> Unit) {
    NavHost(nav, startDestination = RutaMapa) {
        composable<RutaMapa> {
            PantallaMapa(
                viewModel { MapaViewModel(c.reportes, c.ubicacion) }, c.reportes, c.ubicacion, c.fotos,
                alAbrirMenu = alAbrirMenu, alVerDetalle = { nav.navigate(RutaDetalle(it)) },
            )
        }
        composable<RutaMisReportes> {
            PantallaMisReportes(viewModel { MisReportesViewModel(c.reportes) },
                alAbrir = { nav.navigate(RutaDetalle(it)) }, alVolver = { nav.popBackStack() })
        }
        composable<RutaDetalle> { entrada ->
            val id = entrada.toRoute<RutaDetalle>().id
            PantallaDetalleReporte(viewModel { DetalleReporteViewModel(id, c.reportes) }, c.fotos, alVolver = { nav.popBackStack() })
        }
        composable<RutaDenuncia> {
            PantallaDenuncia(
                viewModel { DenunciaViewModel(c.denuncias, c.ubicacion, c.fotos::descartar) }, c.fotos,
                enPestana = false, alVolver = { nav.popBackStack() },
            )
        }
        composable<RutaCuenta> {
            PantallaCuenta(viewModel { CuentaViewModel(c.cuenta, c.sesion) }, alVolver = { nav.popBackStack() })
        }
    }
}

@Composable
private fun ElementoMenu(texto: String, icono: ImageVector, elegido: Boolean, alTocar: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(texto) }, icon = { Icon(icono, contentDescription = null) }, selected = elegido, onClick = alTocar,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

private inline fun <reified T : Any> androidx.navigation.NavBackStackEntry?.esRuta(): Boolean =
    this?.destination?.hasRoute(T::class) == true

private fun androidx.navigation.NavDestination.hasRoute(clase: kotlin.reflect.KClass<*>): Boolean =
    route?.contains(clase.qualifiedName ?: "-") == true

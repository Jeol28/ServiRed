package co.edu.javeriana.servired.app.ui.mapa

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.javeriana.servired.app.datos.fotos.PreparadorFotos
import co.edu.javeriana.servired.app.dominio.Conteo
import co.edu.javeriana.servired.app.dominio.ProveedorUbicacion
import co.edu.javeriana.servired.app.dominio.Reporte
import co.edu.javeriana.servired.app.dominio.ReporteCercano
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.Sentido
import co.edu.javeriana.servired.app.dominio.Ubicacion
import co.edu.javeriana.servired.app.ui.componentes.MapaOsm
import co.edu.javeriana.servired.app.ui.componentes.MarcaMapa
import co.edu.javeriana.servired.app.ui.componentes.PedirUbicacion
import co.edu.javeriana.servired.app.ui.componentes.colorServicio
import co.edu.javeriana.servired.app.ui.componentes.distancia
import co.edu.javeriana.servired.app.ui.componentes.duracion
import co.edu.javeriana.servired.app.ui.reportes.NuevoReporteViewModel
import co.edu.javeriana.servired.app.ui.reportes.ResultadoEnvio
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/** Ámbar del botón de reportar: se distingue del mapa en claro y en oscuro. */
private val Ambar = Color(0xFFF59E0B)

/**
 * Pantalla principal: el mapa a pantalla completa, con el botón grande de
 * reportar, las tarjetas de los cortes y el menú lateral.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaMapa(
    vm: MapaViewModel,
    reportes: RepositorioReportes,
    proveedor: ProveedorUbicacion,
    fotos: PreparadorFotos,
    alAbrirMenu: () -> Unit,
    alVerDetalle: (String) -> Unit,
) {
    val e by vm.estado.collectAsStateWithLifecycle()
    PedirUbicacion { vm.refrescar() }
    // "Tiempo real": se refresca cada minuto mientras el mapa está visible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            vm.refrescar()
        }
    }

    var reporteN by rememberSaveable { mutableIntStateOf(0) }
    var hojaVisible by rememberSaveable { mutableStateOf(false) }
    var eligiendo by rememberSaveable { mutableStateOf(false) }
    var centroMapa by remember { mutableStateOf<Ubicacion?>(null) }
    // Ubicación elegida moviendo el mapa; la hoja de reporte la toma al volver a abrirse.
    var ubicacionElegida by remember { mutableStateOf<Ubicacion?>(null) }

    // "Atrás" cierra primero lo que esté abierto sobre el mapa, en vez de salir de la app.
    BackHandler(enabled = eligiendo || e.seleccion != null) {
        if (eligiendo) {
            eligiendo = false
            hojaVisible = true
        } else {
            vm.seleccionar(null)
        }
    }

    val marcas = e.cercanos.map { MarcaMapa("c:${it.id}", it.ubicacion, colorServicio(it.tipoServicio), it.tipoServicio.emoji) } +
        e.misActivos.map { MarcaMapa("m:${it.id}", it.ubicacion, colorServicio(it.tipoServicio), it.tipoServicio.emoji, propio = true) }

    Box(Modifier.fillMaxSize()) {
        MapaOsm(
            modifier = Modifier.fillMaxSize(),
            marcas = if (eligiendo) emptyList() else marcas,
            zonas = e.zonas,
            usuario = e.ubicacion,
            centro = e.ubicacion,
            zoom = 15.5,
            recentrar = e.recentrar,
            alTocarMarca = vm::seleccionar,
            alElegir = { vm.seleccionar(null) },
            alMoverCentro = { centroMapa = it },
        )

        // Barra superior flotante.
        Row(
            Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BotonRedondo(alTocar = alAbrirMenu, descripcion = "Menú") { Icon(Icons.Filled.Menu, null) }
            Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("SERVI-RED", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        when {
                            e.sinUbicacion -> "Active la ubicación para ver su zona"
                            e.ubicacion == null -> "Buscando su ubicación…"
                            e.cercanos.isEmpty() -> "Sin cortes reportados cerca"
                            e.cercanos.size == 1 -> "1 corte reportado cerca"
                            else -> "${e.cercanos.size} cortes reportados cerca"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            BotonRedondo(alTocar = vm::recentrar, descripcion = "Centrar en mi ubicación") { Icon(Icons.Filled.LocationOn, null) }
        }
        if (e.cargando) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding())

        if (eligiendo) {
            // Pin fijo en el centro: se mueve el mapa, no el pin.
            Text("📍", fontSize = 44.sp, modifier = Modifier.align(Alignment.Center).offset(y = (-22).dp))
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (eligiendo) {
                Tarjeta {
                    Text("Mueva el mapa hasta el lugar del corte", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { eligiendo = false; hojaVisible = true }) { Text("Cancelar") }
                        Button(onClick = {
                            ubicacionElegida = centroMapa
                            eligiendo = false
                            hojaVisible = true
                        }, modifier = Modifier.weight(1f)) { Text("Usar este punto") }
                    }
                }
                return@Column
            }
            if (e.pendientes > 0) {
                Etiqueta(if (e.pendientes == 1) "1 reporte esperando conexión" else "${e.pendientes} reportes esperando conexión")
            }
            (e.error ?: e.aviso)?.let { Aviso(it, error = e.error != null, alCerrar = vm::cerrarAviso) }
            e.cercanoSeleccionado?.let { c ->
                TarjetaCercano(c, e.ocupado, alConfirmar = { vm.validar(Sentido.CONFIRMA) },
                    alNegar = { vm.validar(Sentido.CONTROVIERTE) }, alCerrar = { vm.seleccionar(null) })
            }
            e.propioSeleccionado?.let { r ->
                TarjetaPropia(r, e.ocupado, alYaVolvio = { vm.yaVolvio(r.id) }, alDetalle = { alVerDetalle(r.id) },
                    alCerrar = { vm.seleccionar(null) })
            }
            e.preguntaVolvio?.let { r ->
                TarjetaPregunta(r, e.ocupado, alSi = { vm.yaVolvio(r.id) }, alNo = { vm.todaviaNo(r.id) })
            }
            Button(
                onClick = { reporteN++; hojaVisible = true },
                colors = ButtonDefaults.buttonColors(containerColor = Ambar, contentColor = Color(0xFF1C1917)),
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.fillMaxWidth().height(64.dp).testTag("reportar"),
            ) { Text("⚠  Reportar", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }

    if (hojaVisible) {
        val vmReporte = viewModel(key = "reporte-$reporteN") {
            NuevoReporteViewModel(reportes, proveedor, fotos::descartar)
        }
        val r by vmReporte.estado.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) {
            // La ubicación del reporte: la elegida en el mapa o, si no, la del ciudadano.
            val elegida = ubicacionElegida
            ubicacionElegida = null
            if (elegida != null) vmReporte.cambiar { it.copy(ubicacion = elegida) }
            else if (r.ubicacion == null) e.ubicacion?.let { u -> vmReporte.cambiar { it.copy(ubicacion = u) } }
        }
        LaunchedEffect(r.resultado) {
            val resultado = r.resultado ?: return@LaunchedEffect
            hojaVisible = false
            vm.avisar(
                if (resultado == ResultadoEnvio.ENVIADO) "¡Reporte enviado! Sus vecinos podrán confirmarlo."
                else "Sin conexión: su reporte se enviará solo cuando vuelva la red.",
            )
            vm.refrescar()
        }
        ModalBottomSheet(
            onDismissRequest = { hojaVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            HojaReporte(vmReporte, fotos, alElegirEnMapa = { hojaVisible = false; eligiendo = true })
        }
    }
}

@Composable
private fun BotonRedondo(alTocar: () -> Unit, descripcion: String, contenido: @Composable () -> Unit) {
    Surface(shape = CircleShape, shadowElevation = 4.dp, modifier = Modifier.size(48.dp)) {
        IconButton(onClick = alTocar, modifier = Modifier.semantics { this.contentDescription = descripcion }) { contenido() }
    }
}

@Composable
private fun Tarjeta(contenido: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { contenido() }
    }
}

@Composable
private fun Etiqueta(texto: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 2.dp) {
        Text(texto, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Aviso(texto: String, error: Boolean, alCerrar: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(texto, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            IconButton(onClick = alCerrar) { Icon(Icons.Filled.Close, contentDescription = "Cerrar aviso") }
        }
    }
}

@Composable
private fun Encabezado(emoji: String, titulo: String, detalle: String, alCerrar: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(emoji, fontSize = 32.sp)
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detalle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (alCerrar != null) IconButton(onClick = alCerrar) { Icon(Icons.Filled.Close, contentDescription = "Cerrar") }
    }
}

private fun hace(inicio: Instant): String = "hace " + duracion(Duration.between(inicio, Instant.now()).toMinutes().coerceAtLeast(0))

private fun vecinos(c: Conteo?): String = when (val n = c?.confirmaciones ?: 0) {
    0 -> "Nadie más lo ha confirmado"
    1 -> "1 vecino lo confirmó"
    else -> "$n vecinos lo confirmaron"
} + if (c?.validado == true) " · validado" else ""

@Composable
private fun TarjetaCercano(c: ReporteCercano, ocupado: Boolean, alConfirmar: () -> Unit, alNegar: () -> Unit, alCerrar: () -> Unit) {
    Tarjeta {
        Encabezado(c.tipoServicio.emoji, "${c.tipoServicio.sinServicio} · ${distancia(c.distanciaMetros)}",
            "${c.magnitud.etiqueta} · ${hace(c.inicioEstimado)}\n${vecinos(c.corte)}", alCerrar)
        Text("¿A usted también le afecta?", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = alConfirmar, enabled = !ocupado, modifier = Modifier.weight(1f)) { Text("Sí, también me afecta") }
            OutlinedButton(onClick = alNegar, enabled = !ocupado) { Text("Aquí no") }
        }
    }
}

@Composable
private fun TarjetaPropia(r: Reporte, ocupado: Boolean, alYaVolvio: () -> Unit, alDetalle: () -> Unit, alCerrar: () -> Unit) {
    Tarjeta {
        Encabezado(r.tipoServicio.emoji, "Su reporte · ${r.tipoServicio.sinServicio}",
            "${r.estado.etiqueta} · ${hace(r.inicioEstimado)}\n${vecinos(r.validacionCorte)}", alCerrar)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = alYaVolvio, enabled = !ocupado, modifier = Modifier.weight(1f)) { Text("Ya volvió") }
            OutlinedButton(onClick = alDetalle) { Text("Ver detalle") }
        }
    }
}

@Composable
private fun TarjetaPregunta(r: Reporte, ocupado: Boolean, alSi: () -> Unit, alNo: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(2.dp, colorServicio(r.tipoServicio)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Encabezado(r.tipoServicio.emoji, r.tipoServicio.preguntaVolvio, "${r.tipoServicio.sinServicio} desde ${hace(r.inicioEstimado)}", null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = alSi, enabled = !ocupado, modifier = Modifier.weight(1f).testTag("ya-volvio")) { Text("Sí, ya volvió") }
                TextButton(onClick = alNo) { Text("Todavía no") }
            }
        }
    }
}

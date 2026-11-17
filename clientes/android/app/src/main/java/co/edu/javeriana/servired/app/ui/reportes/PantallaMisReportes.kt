package co.edu.javeriana.servired.app.ui.reportes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.javeriana.servired.app.dominio.EstadoEnvio
import co.edu.javeriana.servired.app.dominio.Reporte
import co.edu.javeriana.servired.app.dominio.ReportePendiente
import co.edu.javeriana.servired.app.ui.componentes.Aviso
import co.edu.javeriana.servired.app.ui.componentes.Etiqueta
import co.edu.javeriana.servired.app.ui.componentes.PantallaConBarra
import co.edu.javeriana.servired.app.ui.componentes.colorEstado
import co.edu.javeriana.servired.app.ui.componentes.colorServicio
import co.edu.javeriana.servired.app.ui.componentes.duracion
import co.edu.javeriana.servired.app.ui.componentes.fechaHora

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaMisReportes(vm: MisReportesViewModel, alNuevo: () -> Unit, alAbrir: (String) -> Unit) {
    val e by vm.estado.collectAsStateWithLifecycle()
    PantallaConBarra(
        titulo = "Mis reportes",
        enPestana = true,
        acciones = { IconButton(onClick = vm::actualizar) { Icon(Icons.Filled.Refresh, contentDescription = "Actualizar") } },
        botonFlotante = {
            ExtendedFloatingActionButton(onClick = alNuevo, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Reportar un corte") })
        },
    ) { margen ->
        PullToRefreshBox(isRefreshing = e.actualizando, onRefresh = vm::actualizar, modifier = Modifier.padding(margen).fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (e.sinConexion) item { Aviso("Sin conexión: ve los reportes guardados en el teléfono.", error = false) }
                e.error?.let { item { Aviso(it, error = true) } }
                if (e.pendientes.isNotEmpty()) {
                    item { Text("Por enviar", style = MaterialTheme.typography.titleMedium) }
                    items(e.pendientes, key = { "p${it.idLocal}" }) { p -> TarjetaPendiente(p) { vm.descartar(p.idLocal) } }
                }
                if (e.reportes.isEmpty() && e.pendientes.isEmpty() && !e.actualizando) {
                    item {
                        Text("Aún no tiene reportes. Si se le fue un servicio, repórtelo con el botón de abajo.",
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 24.dp))
                    }
                }
                items(e.reportes, key = { it.id }) { r -> TarjetaReporte(r) { alAbrir(r.id) } }
            }
        }
    }
}

@Composable
private fun TarjetaReporte(r: Reporte, alTocar: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = alTocar)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.tipoServicio.etiqueta, fontWeight = FontWeight.Bold, color = colorServicio(r.tipoServicio))
                Etiqueta(r.estado.etiqueta, colorEstado(r.estado))
            }
            Text(r.nombreMunicipio ?: r.codigoMunicipio, style = MaterialTheme.typography.bodyMedium)
            Text("Desde ${fechaHora(r.inicioEstimado)}", style = MaterialTheme.typography.bodySmall)
            r.duracionMinutos?.let { Text("Duró ${duracion(it.toLong())}", style = MaterialTheme.typography.bodySmall) }
            if (r.validacionCorte?.validado == true) {
                Text("✓ Validado por la comunidad", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun TarjetaPendiente(p: ReportePendiente, alDescartar: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.datos.tipoServicio.etiqueta, fontWeight = FontWeight.Bold, color = colorServicio(p.datos.tipoServicio))
            Text("Desde ${fechaHora(p.datos.inicioEstimado)}", style = MaterialTheme.typography.bodySmall)
            Text(
                when (p.estado) {
                    EstadoEnvio.PENDIENTE -> "Esperando conexión: se enviará automáticamente."
                    EstadoEnvio.ENVIANDO -> "Enviando…"
                    EstadoEnvio.FALLIDO -> "No se pudo enviar: ${p.error ?: "revise los datos"}"
                },
                color = if (p.estado == EstadoEnvio.FALLIDO) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (p.estado == EstadoEnvio.FALLIDO) TextButton(onClick = alDescartar) { Text("Descartar") }
        }
    }
}

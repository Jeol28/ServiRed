package co.edu.javeriana.servired.app.ui.cercanos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.javeriana.servired.app.dominio.Conteo
import co.edu.javeriana.servired.app.dominio.EstadoReporte
import co.edu.javeriana.servired.app.dominio.ObjetoValidacion
import co.edu.javeriana.servired.app.dominio.ReporteCercano
import co.edu.javeriana.servired.app.dominio.Sentido
import co.edu.javeriana.servired.app.ui.componentes.Aviso
import co.edu.javeriana.servired.app.ui.componentes.Etiqueta
import co.edu.javeriana.servired.app.ui.componentes.MapaOsm
import co.edu.javeriana.servired.app.ui.componentes.MarcaMapa
import co.edu.javeriana.servired.app.ui.componentes.PantallaConBarra
import co.edu.javeriana.servired.app.ui.componentes.PedirUbicacion
import co.edu.javeriana.servired.app.ui.componentes.colorEstado
import co.edu.javeriana.servired.app.ui.componentes.colorServicio
import co.edu.javeriana.servired.app.ui.componentes.distancia
import co.edu.javeriana.servired.app.ui.componentes.fechaHora

/** Cortes reportados por otros a menos de 1 km: confirmar ayuda a priorizarlos (RF-4). */
@Composable
fun PantallaCercanos(vm: CercanosViewModel) {
    val e by vm.estado.collectAsStateWithLifecycle()
    PedirUbicacion { vm.buscar() }
    PantallaConBarra(
        titulo = "Cerca de usted",
        enPestana = true,
        acciones = { IconButton(onClick = vm::buscar) { Icon(Icons.Filled.Refresh, contentDescription = "Buscar de nuevo") } },
    ) { margen ->
        LazyColumn(
            Modifier.padding(margen).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Si a usted también le falta el servicio, confírmelo. Con 3 confirmaciones el corte queda validado " +
                        "por la comunidad. Su ubicación solo se usa para comprobar la distancia y no se guarda.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (e.cargando) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { Aviso(e.aviso, error = false) }
            item { Aviso(e.error, error = true) }
            e.ubicacion?.let { u ->
                item {
                    MapaOsm(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        marcas = e.reportes.map { MarcaMapa(it.id, it.ubicacion, colorServicio(it.tipoServicio)) },
                        seleccion = u,
                        zoom = 14.5,
                    )
                }
            }
            if (!e.cargando && e.ubicacion != null && e.reportes.isEmpty()) {
                item { Text("No hay cortes activos reportados a menos de 1 km.", style = MaterialTheme.typography.bodyLarge) }
            }
            items(e.reportes, key = { it.id }) { c ->
                TarjetaCercano(c, ocupado = e.votando == c.id) { objeto, sentido -> vm.validar(c.id, objeto, sentido) }
            }
        }
    }
}

@Composable
private fun TarjetaCercano(c: ReporteCercano, ocupado: Boolean, alValidar: (ObjetoValidacion, Sentido) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.tipoServicio.etiqueta, fontWeight = FontWeight.Bold, color = colorServicio(c.tipoServicio))
                Etiqueta(c.estado.etiqueta, colorEstado(c.estado))
                Text(distancia(c.distanciaMetros), style = MaterialTheme.typography.bodySmall)
            }
            Text("${c.magnitud.etiqueta} · desde ${fechaHora(c.inicioEstimado)}", style = MaterialTheme.typography.bodySmall)
            Text("Corte: ${resumen(c.corte)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !ocupado, onClick = { alValidar(ObjetoValidacion.CORTE, Sentido.CONFIRMA) }) { Text("También me afecta") }
                OutlinedButton(enabled = !ocupado, onClick = { alValidar(ObjetoValidacion.CORTE, Sentido.CONTROVIERTE) }) { Text("Aquí no hay corte") }
            }
            if (c.estado == EstadoReporte.RESTABLECIDO) {
                Text("Regreso del servicio: ${resumen(c.restablecimiento)}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !ocupado, onClick = { alValidar(ObjetoValidacion.RESTABLECIMIENTO, Sentido.CONFIRMA) }) { Text("Ya volvió") }
                    OutlinedButton(enabled = !ocupado, onClick = { alValidar(ObjetoValidacion.RESTABLECIMIENTO, Sentido.CONTROVIERTE) }) {
                        Text("Aún no hay")
                    }
                }
            }
        }
    }
}

private fun resumen(c: Conteo?): String = when {
    c == null -> "sin validaciones"
    c.validado -> "validado (${c.confirmaciones} confirman)"
    else -> "${c.confirmaciones} confirman, ${c.controversias} no"
}

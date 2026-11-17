package co.edu.javeriana.servired.app.ui.reportes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.javeriana.servired.app.datos.fotos.PreparadorFotos
import co.edu.javeriana.servired.app.dominio.Magnitud
import co.edu.javeriana.servired.app.dominio.Reglas
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.ui.componentes.Aviso
import co.edu.javeriana.servired.app.ui.componentes.MapaOsm
import co.edu.javeriana.servired.app.ui.componentes.PantallaConBarra
import co.edu.javeriana.servired.app.ui.componentes.PedirUbicacion
import co.edu.javeriana.servired.app.ui.componentes.SelectorFotos
import co.edu.javeriana.servired.app.ui.componentes.SelectorHoraPasada
import co.edu.javeriana.servired.app.ui.componentes.SelectorOpciones
import java.time.Instant

@Composable
fun PantallaNuevoReporte(vm: NuevoReporteViewModel, fotos: PreparadorFotos, alTerminar: () -> Unit) {
    val e by vm.estado.collectAsStateWithLifecycle()
    PedirUbicacion { vm.usarMiUbicacion() }
    PantallaConBarra(titulo = "Reportar un corte", alVolver = alTerminar) { margen ->
        Column(
            Modifier.padding(margen).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SelectorOpciones("¿Qué servicio se fue?", TipoServicio.entries, e.tipo, { it.etiqueta }) { t -> vm.cambiar { it.copy(tipo = t) } }
            SelectorOpciones("¿A cuántos afecta?", Magnitud.entries, e.magnitud, { it.etiqueta }) { m -> vm.cambiar { it.copy(magnitud = m) } }
            SelectorHoraPasada("¿Desde cuándo?", e.minutosAtras, maximo = 30L * 24 * 60, ahora = Instant.now()) { m ->
                vm.cambiar { it.copy(minutosAtras = m) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("¿Dónde?", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::usarMiUbicacion, enabled = !e.buscandoUbicacion) {
                        Text(if (e.buscandoUbicacion) "Buscando…" else "Usar mi ubicación")
                    }
                    Text("o toque el mapa", style = MaterialTheme.typography.bodySmall)
                }
                MapaOsm(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    seleccion = e.ubicacion,
                    alElegir = { u -> vm.cambiar { it.copy(ubicacion = u) } },
                )
            }
            OutlinedTextField(
                value = e.descripcion,
                onValueChange = { v -> if (v.length <= Reglas.MAX_DESCRIPCION_REPORTE) vm.cambiar { it.copy(descripcion = v) } },
                label = { Text("Descripción (opcional)") },
                supportingText = { Text("${e.descripcion.length}/${Reglas.MAX_DESCRIPCION_REPORTE}") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("descripcion"),
            )
            SelectorFotos(e.fotos, Reglas.MAX_FOTOS, fotos, vm::agregarFoto, vm::quitarFoto, habilitado = !e.enviando)
            Aviso(e.error, error = true)
            Button(onClick = vm::enviar, enabled = !e.enviando, modifier = Modifier.fillMaxWidth().testTag("enviar-reporte")) {
                Text(if (e.enviando) "Enviando…" else "Enviar reporte")
            }
        }
    }
    e.resultado?.let { r ->
        AlertDialog(
            onDismissRequest = alTerminar,
            confirmButton = { TextButton(onClick = alTerminar) { Text("Entendido") } },
            title = { Text(if (r == ResultadoEnvio.ENVIADO) "Reporte recibido" else "Reporte guardado") },
            text = {
                Text(
                    if (r == ResultadoEnvio.ENVIADO) "Gracias. Puede seguir su atención en «Mis reportes»."
                    else "No hay conexión ahora. El reporte quedó en el teléfono y se enviará solo cuando vuelva la red.",
                )
            },
        )
    }
}

package co.edu.javeriana.servired.app.ui.reportes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.javeriana.servired.app.datos.fotos.PreparadorFotos
import co.edu.javeriana.servired.app.dominio.Conteo
import co.edu.javeriana.servired.app.dominio.Reglas
import co.edu.javeriana.servired.app.dominio.Reporte
import co.edu.javeriana.servired.app.ui.componentes.Aviso
import co.edu.javeriana.servired.app.ui.componentes.Etiqueta
import co.edu.javeriana.servired.app.ui.componentes.ImagenDeBytes
import co.edu.javeriana.servired.app.ui.componentes.MapaOsm
import co.edu.javeriana.servired.app.ui.componentes.MarcaMapa
import co.edu.javeriana.servired.app.ui.componentes.PantallaConBarra
import co.edu.javeriana.servired.app.ui.componentes.SelectorFotos
import co.edu.javeriana.servired.app.ui.componentes.SelectorHoraPasada
import co.edu.javeriana.servired.app.ui.componentes.colorEstado
import co.edu.javeriana.servired.app.ui.componentes.colorServicio
import co.edu.javeriana.servired.app.ui.componentes.duracion
import co.edu.javeriana.servired.app.ui.componentes.fechaHora
import java.time.Duration
import java.time.Instant

@Composable
fun PantallaDetalleReporte(vm: DetalleReporteViewModel, fotos: PreparadorFotos, alVolver: () -> Unit) {
    val e by vm.estado.collectAsStateWithLifecycle()
    PantallaConBarra(titulo = "Reporte", alVolver = alVolver) { margen ->
        val r = e.reporte
        if (r == null) {
            Box(Modifier.padding(margen).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (e.cargando) CircularProgressIndicator() else Aviso(e.error, error = true)
            }
            return@PantallaConBarra
        }
        Column(
            Modifier.padding(margen).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(r.tipoServicio.etiqueta, style = MaterialTheme.typography.headlineSmall, color = colorServicio(r.tipoServicio))
                Etiqueta(r.estado.etiqueta, colorEstado(r.estado))
            }
            Aviso(e.aviso, error = false)
            Aviso(e.error, error = true)
            Datos(r)
            MapaOsm(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                marcas = listOf(MarcaMapa(r.id, r.ubicacion, colorServicio(r.tipoServicio))),
                centro = r.ubicacion,
            )
            if (r.puedeRestablecer) Restablecimiento(vm, r, e.minutosRestablecido, e.comentario, e.ocupado)
            Text("Historia", style = MaterialTheme.typography.titleMedium)
            r.historia.forEach { h ->
                Column {
                    Text("${h.estado.etiqueta} · ${fechaHora(h.fecha)}", fontWeight = FontWeight.SemiBold)
                    h.detalle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            HorizontalDivider()
            Text("Fotos", style = MaterialTheme.typography.titleMedium)
            if (r.evidencias.isEmpty()) Text("Sin fotos.", style = MaterialTheme.typography.bodyMedium)
            r.evidencias.forEach { ev ->
                val bytes = e.fotos[ev.id]
                if (bytes != null) {
                    ImagenDeBytes(bytes, Modifier.fillMaxWidth())
                } else {
                    OutlinedButton(onClick = { vm.verFoto(ev.id) }) { Text("Ver foto (${maxOf(1, ev.tamanoBytes / 1024)} KB)") }
                }
            }
            if (r.puedeAdjuntarFoto) {
                SelectorFotos(
                    fotos = emptyList(), maximo = Reglas.MAX_FOTOS - r.evidencias.size, preparador = fotos,
                    alAgregar = vm::adjuntar, alQuitar = {}, habilitado = !e.ocupado,
                )
            }
        }
    }
}

@Composable
private fun Datos(r: Reporte) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Fila("Municipio", r.nombreMunicipio ?: r.codigoMunicipio)
            Fila("Afecta a", r.magnitud.etiqueta)
            Fila("Inicio", fechaHora(r.inicioEstimado))
            Fila("Reportado", fechaHora(r.fechaRegistro))
            r.fechaRestablecimiento?.let { Fila("Volvió", fechaHora(it)) }
            r.duracionMinutos?.let { Fila("Duración", duracion(it.toLong())) }
            Fila("Validación del corte", conteo(r.validacionCorte))
            if (r.fechaRestablecimiento != null) Fila("Validación del regreso", conteo(r.validacionRestablecimiento))
            r.descripcion?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun Restablecimiento(vm: DetalleReporteViewModel, r: Reporte, minutos: Long, comentario: String, ocupado: Boolean) {
    val maximo = Duration.between(r.inicioEstimado, Instant.now()).toMinutes().coerceAtLeast(0) - 1
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("¿Ya volvió el servicio?", style = MaterialTheme.typography.titleMedium)
            SelectorHoraPasada("¿Cuándo volvió?", minutos, maximo.coerceAtLeast(0), Instant.now()) { m ->
                vm.cambiar { it.copy(minutosRestablecido = m) }
            }
            OutlinedTextField(
                value = comentario,
                onValueChange = { v -> if (v.length <= Reglas.MAX_COMENTARIO_RESTABLECIMIENTO) vm.cambiar { it.copy(comentario = v) } },
                label = { Text("Comentario (opcional)") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = vm::restablecer, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) { Text("El servicio volvió") }
        }
    }
}

@Composable
private fun Fila(etiqueta: String, valor: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(etiqueta, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(valor, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(0.55f))
    }
}

private fun conteo(c: Conteo?): String = when {
    c == null -> "Sin validaciones"
    c.validado -> "Validado (${c.confirmaciones} confirman, ${c.controversias} no)"
    else -> "${c.confirmaciones} confirman, ${c.controversias} no (faltan ${Reglas.CONFIRMACIONES_PARA_VALIDAR - c.netas})"
}

package co.edu.javeriana.servired.app.ui.mapa

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.javeriana.servired.app.datos.fotos.PreparadorFotos
import co.edu.javeriana.servired.app.dominio.Magnitud
import co.edu.javeriana.servired.app.dominio.Reglas
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.ui.componentes.Aviso
import co.edu.javeriana.servired.app.ui.componentes.SelectorFotos
import co.edu.javeriana.servired.app.ui.componentes.colorServicio
import co.edu.javeriana.servired.app.ui.reportes.NuevoReporteViewModel

private val ATAJOS_HORA = listOf(0L to "Ahora", 30L to "Hace 30 min", 60L to "Hace 1 h", 180L to "Hace 3 h", 720L to "Hace 12 h", 1440L to "Ayer")

private val Magnitud.corta: String
    get() = when (this) {
        Magnitud.VIVIENDA -> "Mi casa"
        Magnitud.CUADRA -> "Mi cuadra"
        Magnitud.BARRIO -> "Mi barrio"
        Magnitud.SECTOR -> "Un sector"
    }

/**
 * Reporte rápido (RF-1, RF-2): qué se fue, a cuántos afecta y enviar. La hora
 * es "ahora" y la ubicación la del teléfono, salvo que se cambien; la foto y
 * la descripción son opcionales.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HojaReporte(vm: NuevoReporteViewModel, fotos: PreparadorFotos, alElegirEnMapa: () -> Unit) {
    val e by vm.estado.collectAsStateWithLifecycle()
    var detalles by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("¿Qué se fue?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TipoServicio.entries.forEach { t -> BotonServicio(t, elegido = e.tipo == t) { vm.cambiar { it.copy(tipo = t) } } }
        }

        Text("¿A cuántos afecta?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Magnitud.entries.forEach { m ->
                FilterChip(selected = e.magnitud == m, onClick = { vm.cambiar { it.copy(magnitud = m) } }, label = { Text(m.corta) })
            }
        }

        Text("¿Desde cuándo?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ATAJOS_HORA.forEach { (m, texto) ->
                FilterChip(selected = e.minutosAtras == m, onClick = { vm.cambiar { it.copy(minutosAtras = m) } }, label = { Text(texto) })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    e.buscandoUbicacion -> "📍 Buscando su ubicación…"
                    e.ubicacion == null -> "📍 Falta la ubicación"
                    else -> "📍 Donde está usted ahora"
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = alElegirEnMapa) { Text(if (e.ubicacion == null) "Elegir en el mapa" else "Cambiar") }
        }

        if (!detalles) {
            TextButton(onClick = { detalles = true }) { Text("+ Agregar foto o descripción (opcional)") }
        } else {
            OutlinedTextField(
                value = e.descripcion,
                onValueChange = { v -> if (v.length <= Reglas.MAX_DESCRIPCION_REPORTE) vm.cambiar { it.copy(descripcion = v) } },
                label = { Text("Descripción (opcional)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            SelectorFotos(e.fotos, Reglas.MAX_FOTOS, fotos, vm::agregarFoto, vm::quitarFoto, habilitado = !e.enviando)
        }

        Aviso(e.error, error = true)
        Button(
            onClick = vm::enviar,
            enabled = !e.enviando && e.tipo != null && e.magnitud != null,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("enviar-reporte"),
        ) { Text(if (e.enviando) "Enviando…" else "Enviar reporte", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun BotonServicio(t: TipoServicio, elegido: Boolean, alElegir: () -> Unit) {
    val color = colorServicio(t)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (elegido) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (elegido) BorderStroke(3.dp, color) else null,
        modifier = Modifier.size(width = 100.dp, height = 92.dp)
            .selectable(selected = elegido, role = Role.RadioButton, onClick = alElegir)
            .testTag("servicio-${t.name}"),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(6.dp)) {
            Text(t.emoji, fontSize = 30.sp)
            Text(t.corto, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

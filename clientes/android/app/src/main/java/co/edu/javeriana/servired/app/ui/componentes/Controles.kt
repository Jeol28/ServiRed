package co.edu.javeriana.servired.app.ui.componentes

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import java.time.Duration
import java.time.Instant

/** Pantalla con barra superior. `enPestana` evita duplicar los márgenes de la barra inferior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaConBarra(
    titulo: String,
    alVolver: (() -> Unit)? = null,
    enPestana: Boolean = false,
    acciones: @Composable RowScope.() -> Unit = {},
    botonFlotante: @Composable () -> Unit = {},
    contenido: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titulo) },
                navigationIcon = {
                    if (alVolver != null) {
                        IconButton(onClick = alVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                    }
                },
                actions = acciones,
            )
        },
        floatingActionButton = botonFlotante,
        contentWindowInsets = if (enPestana) WindowInsets(0) else WindowInsets.navigationBars,
        content = contenido,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SelectorOpciones(titulo: String, opciones: List<T>, elegida: T?, etiqueta: (T) -> String, alElegir: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            opciones.forEach { o -> FilterChip(selected = o == elegida, onClick = { alElegir(o) }, label = { Text(etiqueta(o)) }) }
        }
    }
}

/** Elegir hace cuánto ocurrió algo (atajos y pasos de 15 minutos). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectorHoraPasada(titulo: String, minutos: Long, maximo: Long, ahora: Instant, alCambiar: (Long) -> Unit) {
    val atajos = listOf(0L to "Ahora", 15L to "15 min", 30L to "30 min", 60L to "1 h", 120L to "2 h", 240L to "4 h", 480L to "8 h", 1440L to "1 día")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            atajos.filter { it.first <= maximo }.forEach { (m, texto) ->
                FilterChip(selected = minutos == m, onClick = { alCambiar(m) }, label = { Text(texto) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { alCambiar(minOf(maximo, minutos + 15)) }, enabled = minutos < maximo) { Text("15 min antes") }
            OutlinedButton(onClick = { alCambiar(maxOf(0, minutos - 15)) }, enabled = minutos > 0) { Text("15 min después") }
        }
        Text(
            (if (minutos == 0L) "Ahora" else "Hace ${duracion(minutos)}") + " · " + fechaHora(ahora.minus(Duration.ofMinutes(minutos))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Aviso(texto: String?, error: Boolean) {
    if (texto == null) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            texto,
            modifier = Modifier.padding(12.dp),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun Etiqueta(texto: String, color: Color) {
    Text(
        texto,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** Pide el permiso de ubicación (una vez) y avisa el resultado. */
@Composable
fun PedirUbicacion(alResolver: (Boolean) -> Unit) {
    val lanzador = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        alResolver(r.values.any { it })
    }
    LaunchedEffect(Unit) {
        lanzador.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

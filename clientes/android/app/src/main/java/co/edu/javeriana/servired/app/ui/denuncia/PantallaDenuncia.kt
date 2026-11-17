package co.edu.javeriana.servired.app.ui.denuncia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.javeriana.servired.app.datos.fotos.PreparadorFotos
import co.edu.javeriana.servired.app.dominio.Reglas
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.ui.componentes.Aviso
import co.edu.javeriana.servired.app.ui.componentes.MapaOsm
import co.edu.javeriana.servired.app.ui.componentes.PantallaConBarra
import co.edu.javeriana.servired.app.ui.componentes.SelectorFotos
import co.edu.javeriana.servired.app.ui.componentes.SelectorOpciones

/**
 * Denuncia anónima (RF-5, RNF-SEG4). No pide permiso de ubicación al abrir:
 * el ciudadano decide si usa la del teléfono o marca el punto en el mapa.
 */
@Composable
fun PantallaDenuncia(vm: DenunciaViewModel, fotos: PreparadorFotos, enPestana: Boolean, alVolver: (() -> Unit)? = null) {
    val e by vm.estado.collectAsStateWithLifecycle()
    PantallaConBarra(titulo = "Denuncia anónima", alVolver = alVolver, enPestana = enPestana) { margen ->
        Column(
            Modifier.padding(margen).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Aviso(
                "Para conexiones ilegales, fraudes o manipulación de medidores. No se envía su cuenta ni ningún dato " +
                    "del teléfono; la foto viaja sin metadatos (GPS, modelo, fecha). No escriba datos personales.",
                error = false,
            )
            if (e.enviada) {
                Text("Denuncia recibida", style = MaterialTheme.typography.headlineSmall)
                Text("Un moderador la revisará. No se le asigna un número de seguimiento para no vincularla con usted.")
                Button(onClick = vm::nueva) { Text("Hacer otra denuncia") }
                return@PantallaConBarra
            }
            SelectorOpciones("Servicio", TipoServicio.entries, e.tipo, { it.etiqueta }) { t -> vm.cambiar { it.copy(tipo = t) } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Toque el mapa donde ocurre", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = vm::usarMiUbicacion) { Text("Usar mi ubicación") }
                MapaOsm(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    seleccion = e.ubicacion,
                    alElegir = { u -> vm.cambiar { it.copy(ubicacion = u) } },
                )
            }
            OutlinedTextField(
                value = e.descripcion,
                onValueChange = { v -> if (v.length <= Reglas.DENUNCIA_DESCRIPCION.last) vm.cambiar { it.copy(descripcion = v) } },
                label = { Text("¿Qué ocurre?") },
                supportingText = { Text("Entre ${Reglas.DENUNCIA_DESCRIPCION.first} y ${Reglas.DENUNCIA_DESCRIPCION.last} caracteres") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            SelectorFotos(listOfNotNull(e.foto), 1, fotos, { vm.ponerFoto(it) }, { vm.ponerFoto(null) }, habilitado = !e.enviando)
            Aviso(e.error, error = true)
            Button(onClick = vm::enviar, enabled = !e.enviando, modifier = Modifier.fillMaxWidth()) {
                Text(if (e.enviando) "Enviando…" else "Enviar denuncia")
            }
        }
    }
}

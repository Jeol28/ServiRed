package co.edu.javeriana.servired.app.ui.cuenta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.javeriana.servired.app.ui.componentes.Aviso
import co.edu.javeriana.servired.app.ui.componentes.PantallaConBarra

@Composable
fun PantallaCuenta(vm: CuentaViewModel, alVolver: () -> Unit) {
    val e by vm.estado.collectAsStateWithLifecycle()
    var confirmarEliminar by remember { mutableStateOf(false) }
    PantallaConBarra(titulo = "Mi cuenta", alVolver = alVolver) { margen ->
        Column(
            Modifier.padding(margen).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            e.cuenta?.let { Text(it.correo, style = MaterialTheme.typography.titleMedium) }
            Aviso(e.aviso, error = false)
            Aviso(e.error, error = true)
            OutlinedTextField(
                value = e.nombre, onValueChange = { v -> vm.cambiar { it.copy(nombre = v) } },
                label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = vm::guardarNombre, enabled = !e.ocupado) { Text("Guardar nombre") }
            HorizontalDivider()
            Text("Cambiar clave", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = e.claveActual, onValueChange = { v -> vm.cambiar { it.copy(claveActual = v) } },
                label = { Text("Clave actual") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = e.claveNueva, onValueChange = { v -> vm.cambiar { it.copy(claveNueva = v) } },
                label = { Text("Clave nueva (10 a 64 caracteres)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Text("Al cambiarla se cierran todas sus sesiones.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::cambiarClave, enabled = !e.ocupado && e.claveActual.isNotBlank() && e.claveNueva.isNotBlank()) {
                Text("Cambiar clave")
            }
            HorizontalDivider()
            Button(onClick = vm::cerrarSesion, enabled = !e.ocupado, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
            TextButton(
                onClick = { confirmarEliminar = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Eliminar mi cuenta") }
            Text(
                "Al cerrar sesión se borran del teléfono sus reportes guardados y los que no se han enviado.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (confirmarEliminar) {
        AlertDialog(
            onDismissRequest = { confirmarEliminar = false },
            title = { Text("¿Eliminar su cuenta?") },
            text = {
                Text("Se borran su correo y su nombre y se cierran sus sesiones. Sus reportes se conservan sin datos que lo " +
                    "identifiquen, porque sirven a los indicadores públicos. No se puede deshacer.")
            },
            confirmButton = {
                TextButton(onClick = { confirmarEliminar = false; vm.eliminarCuenta() }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmarEliminar = false }) { Text("Cancelar") } },
        )
    }
}

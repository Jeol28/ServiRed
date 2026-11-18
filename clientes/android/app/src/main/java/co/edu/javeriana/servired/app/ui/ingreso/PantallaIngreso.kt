package co.edu.javeriana.servired.app.ui.ingreso

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.javeriana.servired.app.ui.componentes.Aviso

@Composable
fun PantallaIngreso(vm: IngresoViewModel, alDenunciar: () -> Unit, alVolver: (() -> Unit)? = null) {
    val e by vm.estado.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (alVolver != null) TextButton(onClick = alVolver) { Text("← Volver al mapa") }
                Spacer(Modifier.height(if (alVolver != null) 0.dp else 24.dp))
                Text("SERVI-RED", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    if (e.registro) "Con su cuenta puede reportar cortes, confirmar los de sus vecinos y avisar cuando vuelva el servicio."
                    else "Reporte los cortes de energía, gas, agua, alcantarillado y aseo, y siga su atención.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(if (e.registro) "Crear cuenta" else "Ingresar", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = e.correo, onValueChange = { v -> vm.cambiar { it.copy(correo = v) } },
                    label = { Text("Correo") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("correo"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = e.clave, onValueChange = { v -> vm.cambiar { it.copy(clave = v) } },
                    label = { Text("Clave") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("clave"),
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = if (e.registro) ({ Text("Entre 10 y 64 caracteres.") }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (e.registro) {
                    OutlinedTextField(
                        value = e.nombre, onValueChange = { v -> vm.cambiar { it.copy(nombre = v) } },
                        label = { Text("Nombre (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = e.aceptaTratamiento, onCheckedChange = { v -> vm.cambiar { it.copy(aceptaTratamiento = v) } },
                            modifier = Modifier.testTag("acepta"))
                        Text(
                            "Autorizo el tratamiento de mis datos personales para gestionar mis reportes (Ley 1581 de 2012). " +
                                "Puedo eliminar mi cuenta cuando quiera.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Aviso(e.error, error = true)
                Button(onClick = vm::enviar, enabled = !e.cargando, modifier = Modifier.fillMaxWidth().testTag("enviar")) {
                    Text(if (e.cargando) "Un momento…" else if (e.registro) "Crear cuenta" else "Ingresar")
                }
                TextButton(onClick = vm::alternarModo, modifier = Modifier.fillMaxWidth()) {
                    Text(if (e.registro) "Ya tengo cuenta: ingresar" else "No tengo cuenta: registrarme")
                }
                HorizontalDivider()
                Text("¿Sabe de una conexión ilegal o un fraude? Puede denunciarlo sin cuenta y sin dejar ningún dato suyo.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = alDenunciar, modifier = Modifier.fillMaxWidth()) { Text("Hacer una denuncia anónima") }
            }
        }
    }
}

package co.edu.javeriana.servired.app.ui.componentes

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.edu.javeriana.servired.app.datos.fotos.PreparadorFotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tomar una foto (CameraX) o elegirla de la galería (selector del sistema,
 * sin permiso de almacenamiento). Cada foto se prepara (enderezada y sin
 * EXIF) antes de entregarla.
 */
@Composable
fun SelectorFotos(
    fotos: List<String>,
    maximo: Int,
    preparador: PreparadorFotos,
    alAgregar: (String) -> Unit,
    alQuitar: (String) -> Unit,
    habilitado: Boolean = true,
) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    var camara by remember { mutableStateOf(false) }
    var preparando by remember { mutableStateOf(false) }

    fun preparar(uri: Uri, borrarOrigen: Boolean) {
        preparando = true
        alcance.launch {
            val ruta = withContext(Dispatchers.IO) {
                preparador.preparar(uri).also { if (borrarOrigen) uri.path?.let { java.io.File(it).delete() } }
            }
            preparando = false
            ruta?.let(alAgregar)
        }
    }

    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { preparar(it, borrarOrigen = false) }
    }
    val permisoCamara = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) camara = true
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Fotos (opcional, hasta $maximo)", style = MaterialTheme.typography.titleSmall)
        if (fotos.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fotos.forEach { ruta ->
                    Box {
                        Miniatura(ruta, Modifier.size(96.dp))
                        FilledIconButton(
                            onClick = { alQuitar(ruta) },
                            modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                        ) { Icon(Icons.Filled.Close, contentDescription = "Quitar foto", tint = Color.White) }
                    }
                }
            }
        }
        if (preparando) CircularProgressIndicator(Modifier.size(24.dp))
        if (fotos.size < maximo) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = habilitado && !preparando, onClick = {
                    if (ContextCompat.checkSelfPermission(contexto, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        camara = true
                    } else {
                        permisoCamara.launch(Manifest.permission.CAMERA)
                    }
                }) { Text("Tomar foto") }
                OutlinedButton(enabled = habilitado && !preparando, onClick = {
                    galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Elegir de la galería") }
            }
        }
    }

    if (camara) {
        Dialog(onDismissRequest = { camara = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CamaraCaptura(
                archivo = { preparador.archivoCaptura() },
                alCapturar = { uri ->
                    camara = false
                    preparar(uri, borrarOrigen = true)
                },
                alCerrar = { camara = false },
            )
        }
    }
}

@Composable
private fun CamaraCaptura(archivo: () -> java.io.File, alCapturar: (Uri) -> Unit, alCerrar: () -> Unit) {
    val contexto = LocalContext.current
    val dueno = LocalLifecycleOwner.current
    val vista = remember { PreviewView(contexto) }
    val captura = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var error by remember { mutableStateOf<String?>(null) }
    var tomando by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val futuro = ProcessCameraProvider.getInstance(contexto)
        futuro.addListener({
            runCatching {
                val proveedor = futuro.get()
                val vistaPrevia = Preview.Builder().build().also { it.setSurfaceProvider(vista.surfaceProvider) }
                proveedor.unbindAll()
                proveedor.bindToLifecycle(dueno, CameraSelector.DEFAULT_BACK_CAMERA, vistaPrevia, captura)
            }.onFailure { error = "No se pudo abrir la cámara." }
        }, ContextCompat.getMainExecutor(contexto))
        onDispose { runCatching { if (futuro.isDone) futuro.get().unbindAll() } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { vista }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = Color.White) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = alCerrar) { Text("Cancelar", color = Color.White) }
                Button(enabled = !tomando && error == null, onClick = {
                    tomando = true
                    val destino = archivo()
                    captura.takePicture(
                        ImageCapture.OutputFileOptions.Builder(destino).build(),
                        ContextCompat.getMainExecutor(contexto),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(r: ImageCapture.OutputFileResults) = alCapturar(Uri.fromFile(destino))
                            override fun onError(e: ImageCaptureException) {
                                tomando = false
                                error = "No se pudo tomar la foto."
                            }
                        },
                    )
                }) { Text("Capturar") }
            }
        }
    }
}

@Composable
fun Miniatura(ruta: String, modifier: Modifier = Modifier) {
    val imagen by produceState<ImageBitmap?>(null, ruta) {
        value = withContext(Dispatchers.IO) { decodificar(ruta = ruta, bytes = null, lado = 256) }
    }
    ImagenOEspera(imagen, modifier)
}

@Composable
fun ImagenDeBytes(bytes: ByteArray, modifier: Modifier = Modifier) {
    val imagen by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.IO) { decodificar(ruta = null, bytes = bytes, lado = 1280) }
    }
    ImagenOEspera(imagen, modifier, ContentScale.FillWidth)
}

@Composable
private fun ImagenOEspera(imagen: ImageBitmap?, modifier: Modifier, escala: ContentScale = ContentScale.Crop) {
    if (imagen == null) {
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        Image(imagen, contentDescription = "Foto del reporte", contentScale = escala, modifier = modifier.clip(RoundedCornerShape(8.dp)))
    }
}

private fun decodificar(ruta: String?, bytes: ByteArray?, lado: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (ruta != null) BitmapFactory.decodeFile(ruta, o) else BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size, o)
    var m = 1
    while (maxOf(o.outWidth, o.outHeight) / (m * 2) >= lado) m *= 2
    val opciones = BitmapFactory.Options().apply { inSampleSize = m }
    (if (ruta != null) BitmapFactory.decodeFile(ruta, opciones) else BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size, opciones))
        ?.asImageBitmap()
}.getOrNull()

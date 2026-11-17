package co.edu.javeriana.servired.app.ui.componentes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import co.edu.javeriana.servired.app.dominio.EstadoReporte
import co.edu.javeriana.servired.app.dominio.TipoServicio

private val Claro = lightColorScheme(
    primary = Color(0xFF0B5394),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F7),
    onPrimaryContainer = Color(0xFF072F55),
    secondary = Color(0xFFB45309),
    error = Color(0xFFB42318),
    background = Color(0xFFF6F7F9),
    surface = Color.White,
)

private val Oscuro = darkColorScheme(
    primary = Color(0xFF8EC1F0),
    onPrimary = Color(0xFF0B1622),
    primaryContainer = Color(0xFF123A61),
    onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFFFDB022),
    error = Color(0xFFF97066),
    background = Color(0xFF0F141A),
    surface = Color(0xFF18202A),
)

@Composable
fun TemaServiRed(contenido: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Oscuro else Claro, content = contenido)
}

/** Colores por servicio, los mismos de los portales web. */
fun colorServicio(t: TipoServicio): Color = when (t) {
    TipoServicio.ENERGIA -> Color(0xFFD97706)
    TipoServicio.GAS -> Color(0xFFDC2626)
    TipoServicio.ACUEDUCTO -> Color(0xFF2563EB)
    TipoServicio.ALCANTARILLADO -> Color(0xFF7C3AED)
    TipoServicio.ASEO -> Color(0xFF059669)
}

fun colorEstado(e: EstadoReporte): Color = when (e) {
    EstadoReporte.RECIBIDO -> Color(0xFF64748B)
    EstadoReporte.EN_ATENCION -> Color(0xFFD97706)
    EstadoReporte.RESTABLECIDO -> Color(0xFF059669)
    EstadoReporte.CERRADO -> Color(0xFF475569)
}

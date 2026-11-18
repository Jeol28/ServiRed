package co.edu.javeriana.servired.app.ui.mapa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import co.edu.javeriana.servired.app.dominio.TipoServicio

/** Símbolo de cada servicio en el mapa y en el formulario de reporte. */
val TipoServicio.emoji: String
    get() = when (this) {
        TipoServicio.ENERGIA -> "⚡"
        TipoServicio.ACUEDUCTO -> "💧"
        TipoServicio.GAS -> "🔥"
        TipoServicio.ALCANTARILLADO -> "🌊"
        TipoServicio.ASEO -> "🗑️"
    }

/** Cómo lo dice el ciudadano: "se fue la luz", no "interrupción de energía". */
val TipoServicio.sinServicio: String
    get() = when (this) {
        TipoServicio.ENERGIA -> "Sin luz"
        TipoServicio.ACUEDUCTO -> "Sin agua"
        TipoServicio.GAS -> "Sin gas"
        TipoServicio.ALCANTARILLADO -> "Alcantarillado"
        TipoServicio.ASEO -> "Sin recolección de basura"
    }

/** Nombre corto para los botones del reporte. */
val TipoServicio.corto: String
    get() = when (this) {
        TipoServicio.ENERGIA -> "Luz"
        TipoServicio.ACUEDUCTO -> "Agua"
        TipoServicio.GAS -> "Gas"
        TipoServicio.ALCANTARILLADO -> "Alcantarillado"
        TipoServicio.ASEO -> "Basura"
    }

/** Pregunta para un reporte propio activo. */
val TipoServicio.preguntaVolvio: String
    get() = when (this) {
        TipoServicio.ENERGIA -> "¿Ya volvió la luz?"
        TipoServicio.ACUEDUCTO -> "¿Ya volvió el agua?"
        TipoServicio.GAS -> "¿Ya volvió el gas?"
        TipoServicio.ALCANTARILLADO -> "¿Ya se solucionó el alcantarillado?"
        TipoServicio.ASEO -> "¿Ya pasó el carro de la basura?"
    }

/**
 * Ícono de un marcador: círculo del color del servicio con su símbolo. Los
 * reportes propios llevan un anillo oscuro para distinguirlos.
 */
fun iconoMarcador(emoji: String?, color: Color, propio: Boolean, densidad: Float): Bitmap {
    val lado = (44 * densidad).toInt()
    val bitmap = createBitmap(lado, lado)
    val lienzo = Canvas(bitmap)
    val centro = lado / 2f
    val borde = (if (propio) 4.5f else 3f) * densidad
    val pintura = Paint(Paint.ANTI_ALIAS_FLAG)
    pintura.color = if (propio) 0xFF0F172A.toInt() else android.graphics.Color.WHITE
    lienzo.drawCircle(centro, centro, centro, pintura)
    pintura.color = color.toArgb()
    lienzo.drawCircle(centro, centro, centro - borde, pintura)
    if (emoji != null) {
        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = lado * 0.48f
            textAlign = Paint.Align.CENTER
        }
        val alto = texto.fontMetrics.let { it.descent + it.ascent }
        lienzo.drawText(emoji, centro, centro - alto / 2, texto)
    }
    return bitmap
}

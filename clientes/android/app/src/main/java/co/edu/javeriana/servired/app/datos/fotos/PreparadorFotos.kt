package co.edu.javeriana.servired.app.datos.fotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import co.edu.javeriana.servired.app.dominio.Reglas
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Prepara una foto antes de guardarla o enviarla: la endereza según su EXIF,
 * la reduce a 2560 px como máximo y la vuelve a codificar en JPEG. Al
 * recodificarla se descartan TODOS los metadatos (GPS, modelo, fecha); el
 * servidor lo vuelve a hacer, pero así nunca salen del teléfono.
 */
class PreparadorFotos(private val contexto: Context) {
    private val carpeta get() = File(contexto.filesDir, "fotos").apply { mkdirs() }

    /** Archivo temporal para que CameraX escriba la captura. */
    fun archivoCaptura(): File = File(contexto.cacheDir, "captura-${UUID.randomUUID()}.jpg")

    /** Devuelve la ruta del JPEG preparado o null si la imagen no se pudo leer. */
    fun preparar(origen: Uri): String? = runCatching {
        val orientacion = contexto.contentResolver.openInputStream(origen)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contexto.contentResolver.openInputStream(origen)!!.use { BitmapFactory.decodeStream(it, null, limites) }
        var muestreo = 1
        while (max(limites.outWidth, limites.outHeight) / (muestreo * 2) >= MAX_LADO) muestreo *= 2
        val original = contexto.contentResolver.openInputStream(origen)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestreo })
        }!!
        val derecha = enderezar(escalar(original), orientacion)
        val destino = File(carpeta, "${UUID.randomUUID()}.jpg")
        destino.writeBytes(comprimir(derecha))
        destino.absolutePath
    }.getOrNull()

    private fun escalar(b: Bitmap): Bitmap {
        val lado = max(b.width, b.height)
        if (lado <= MAX_LADO) return b
        val f = MAX_LADO.toFloat() / lado
        return b.scale((b.width * f).toInt(), (b.height * f).toInt())
    }

    private fun enderezar(b: Bitmap, orientacion: Int): Bitmap {
        val m = Matrix()
        when (orientacion) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun comprimir(b: Bitmap): ByteArray {
        var calidad = 85
        while (true) {
            val salida = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, calidad, salida)
            if (salida.size() <= Reglas.MAX_BYTES_FOTO || calidad <= 40) return salida.toByteArray()
            calidad -= 15
        }
    }

    fun descartar(ruta: String) {
        File(ruta).delete()
    }

    private companion object {
        const val MAX_LADO = 2560
    }
}

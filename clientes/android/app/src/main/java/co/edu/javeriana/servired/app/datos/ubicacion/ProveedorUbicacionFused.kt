package co.edu.javeriana.servired.app.datos.ubicacion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import co.edu.javeriana.servired.app.dominio.ProveedorUbicacion
import co.edu.javeriana.servired.app.dominio.Ubicacion
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Ubicación puntual con Fused Location. Nunca se rastrea en segundo plano. */
class ProveedorUbicacionFused(private val contexto: Context) : ProveedorUbicacion {
    private val cliente = LocationServices.getFusedLocationProviderClient(contexto)

    override suspend fun actual(): Ubicacion? {
        val permitido = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(contexto, it) == PackageManager.PERMISSION_GRANTED }
        if (!permitido) return null
        return try {
            val cancelacion = CancellationTokenSource()
            val l = withTimeoutOrNull(15_000) {
                cliente.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancelacion.token).await()
            } ?: cliente.lastLocation.await()
            l?.let { Ubicacion(redondear(it.latitude), redondear(it.longitude)) }
        } catch (e: SecurityException) {
            null
        }
    }

    /** 5 decimales (~1 m) bastan para un reporte. */
    private fun redondear(v: Double) = Math.round(v * 1e5) / 1e5
}

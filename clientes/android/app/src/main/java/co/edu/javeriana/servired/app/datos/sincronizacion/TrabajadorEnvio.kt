package co.edu.javeriana.servired.app.datos.sincronizacion

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import co.edu.javeriana.servired.app.dominio.ProgramadorEnvio
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import java.util.concurrent.TimeUnit

/** Envía los reportes pendientes cuando hay red; si algo falla, WorkManager reintenta con espera exponencial. */
class TrabajadorEnvio(
    contexto: Context,
    parametros: WorkerParameters,
    private val reportes: RepositorioReportes,
) : CoroutineWorker(contexto, parametros) {
    override suspend fun doWork(): Result = if (reportes.enviarPendientes()) Result.success() else Result.retry()
}

class FabricaTrabajadores(private val reportes: () -> RepositorioReportes) : WorkerFactory() {
    override fun createWorker(contexto: Context, clase: String, parametros: WorkerParameters): ListenableWorker? =
        if (clase == TrabajadorEnvio::class.java.name) TrabajadorEnvio(contexto, parametros, reportes()) else null
}

class ProgramadorWorkManager(private val contexto: Context) : ProgramadorEnvio {
    override fun programar() {
        val trabajo = OneTimeWorkRequestBuilder<TrabajadorEnvio>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(contexto).enqueueUniqueWork("envio-reportes", ExistingWorkPolicy.KEEP, trabajo)
    }
}

package co.edu.javeriana.servired.app

import android.app.Application
import androidx.work.Configuration
import co.edu.javeriana.servired.app.datos.sincronizacion.FabricaTrabajadores
import org.maplibre.android.MapLibre

class ServiRedApp : Application(), Configuration.Provider {
    lateinit var contenedor: Contenedor
        private set

    override fun onCreate() {
        super.onCreate()
        contenedor = Contenedor(this)
        MapLibre.getInstance(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(FabricaTrabajadores { contenedor.reportes }).build()
}

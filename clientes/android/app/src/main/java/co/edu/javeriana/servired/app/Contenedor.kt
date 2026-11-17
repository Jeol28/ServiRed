package co.edu.javeriana.servired.app

import android.content.Context
import androidx.room.Room
import co.edu.javeriana.servired.app.datos.RepositorioCuentaImpl
import co.edu.javeriana.servired.app.datos.RepositorioDenunciasImpl
import co.edu.javeriana.servired.app.datos.RepositorioReportesImpl
import co.edu.javeriana.servired.app.datos.RepositorioSesionImpl
import co.edu.javeriana.servired.app.datos.fotos.PreparadorFotos
import co.edu.javeriana.servired.app.datos.local.BaseDatos
import co.edu.javeriana.servired.app.datos.remoto.crearApiCiudadano
import co.edu.javeriana.servired.app.datos.remoto.crearApiPublica
import co.edu.javeriana.servired.app.datos.remoto.jsonServiRed
import co.edu.javeriana.servired.app.datos.sesion.CofreKeystore
import co.edu.javeriana.servired.app.datos.sincronizacion.ProgramadorWorkManager
import co.edu.javeriana.servired.app.datos.ubicacion.ProveedorUbicacionFused
import co.edu.javeriana.servired.app.dominio.ProveedorUbicacion
import co.edu.javeriana.servired.app.dominio.RepositorioCuenta
import co.edu.javeriana.servired.app.dominio.RepositorioDenuncias
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.RepositorioSesion

/** Inyección de dependencias manual: una instancia de cada pieza para toda la app. */
class Contenedor(contexto: Context, apiBase: String = BuildConfig.API_BASE) {
    private val json = jsonServiRed
    private val apiPublica = crearApiPublica(apiBase, json)
    private val baseDatos = Room.databaseBuilder(contexto, BaseDatos::class.java, "servired.db").build()

    private val sesionImpl = RepositorioSesionImpl(apiPublica, CofreKeystore(contexto), json) {
        // Al cerrar sesión no quedan datos personales en el teléfono.
        baseDatos.reportes().borrarTodo()
        baseDatos.pendientes().borrarTodo()
    }
    private val apiCiudadano = crearApiCiudadano(apiBase, sesionImpl.gestor, apiPublica, json)

    val sesion: RepositorioSesion = sesionImpl
    val reportes: RepositorioReportes = RepositorioReportesImpl(
        apiCiudadano, baseDatos.reportes(), baseDatos.pendientes(), json, ProgramadorWorkManager(contexto),
    )
    val denuncias: RepositorioDenuncias = RepositorioDenunciasImpl(apiPublica, json)
    val cuenta: RepositorioCuenta = RepositorioCuentaImpl(apiCiudadano, sesion)
    val ubicacion: ProveedorUbicacion = ProveedorUbicacionFused(contexto)
    val fotos = PreparadorFotos(contexto)
}

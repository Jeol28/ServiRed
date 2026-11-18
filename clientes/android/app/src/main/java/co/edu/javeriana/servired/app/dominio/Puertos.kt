package co.edu.javeriana.servired.app.dominio

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Puertos del dominio: los ViewModel solo conocen estas interfaces. La capa
 * de datos (Retrofit, Room, Play Services) las implementa; las pruebas usan
 * dobles en memoria.
 */

interface RepositorioSesion {
    val estado: StateFlow<EstadoSesion>
    /** Restaura la sesión guardada (token de refresco cifrado). */
    suspend fun restaurar()
    suspend fun ingresar(correo: String, clave: String): Resultado<Unit>
    suspend fun registrar(correo: String, clave: String, nombre: String?): Resultado<Unit>
    suspend fun cerrar()
}

interface RepositorioReportes {
    fun misReportes(): Flow<List<Reporte>>
    fun pendientes(): Flow<List<ReportePendiente>>
    suspend fun actualizar(): Resultado<Unit>
    suspend fun obtener(id: String): Resultado<Reporte>
    /**
     * Guarda el reporte en el teléfono y lo intenta enviar. Devuelve true si
     * llegó al servidor y false si quedó pendiente (se enviará al haber red).
     */
    suspend fun crear(nuevo: NuevoReporte, fotos: List<String>): Resultado<Boolean>
    /** Envía lo pendiente. Devuelve false si algo debe reintentarse más tarde. */
    suspend fun enviarPendientes(): Boolean
    suspend fun descartarPendiente(idLocal: Long)
    suspend fun adjuntarFoto(idReporte: String, ruta: String): Resultado<Reporte>
    suspend fun foto(idReporte: String, idEvidencia: String): Resultado<ByteArray>
    suspend fun restablecer(id: String, fecha: Instant, comentario: String?): Resultado<Reporte>
    suspend fun cercanos(desde: Ubicacion): Resultado<List<ReporteCercano>>
    /** Zonas con cortes de los últimos 30 días en todo el país (agregadas y anónimas). */
    suspend fun zonasPublicas(): Resultado<List<Zona>>
    suspend fun validar(id: String, objeto: ObjetoValidacion, sentido: Sentido, desde: Ubicacion): Resultado<ResultadoValidacion>
}

interface RepositorioDenuncias {
    suspend fun enviar(denuncia: NuevaDenuncia, foto: String?): Resultado<Unit>
}

interface RepositorioCuenta {
    suspend fun yo(): Resultado<Cuenta>
    suspend fun cambiarNombre(nombre: String): Resultado<Cuenta>
    suspend fun cambiarClave(actual: String, nueva: String): Resultado<Unit>
    suspend fun eliminar(): Resultado<Unit>
}

interface ProveedorUbicacion {
    /** Ubicación actual o null si no hay permiso o señal. */
    suspend fun actual(): Ubicacion?
}

/** Programa el envío de pendientes cuando haya red (WorkManager en la app). */
fun interface ProgramadorEnvio {
    fun programar()
}

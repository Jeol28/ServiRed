package co.edu.javeriana.servired.app.datos.remoto

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

const val CABECERA_CORRELACION = "X-Correlation-Id"

/** Operaciones del ciudadano autenticado (pasan por los interceptores de JWT y correlación). */
interface ApiCiudadano {
    @GET("api/v1/reportes")
    suspend fun misReportes(@Query("pagina") pagina: Int, @Query("tamano") tamano: Int): PaginaReportesDto

    /** El X-Correlation-Id es la clave de idempotencia: el mismo valor nunca crea dos reportes. */
    @POST("api/v1/reportes")
    suspend fun crearReporte(@Header(CABECERA_CORRELACION) correlacion: String, @Body cuerpo: NuevoReporteDto): ReporteDto

    @GET("api/v1/reportes/{id}")
    suspend fun reporte(@Path("id") id: String): ReporteDto

    @Multipart
    @POST("api/v1/reportes/{id}/evidencias")
    suspend fun adjuntarFoto(
        @Header(CABECERA_CORRELACION) correlacion: String,
        @Path("id") id: String,
        @Part foto: MultipartBody.Part,
    ): ReporteDto

    @GET("api/v1/reportes/{id}/evidencias/{evidencia}")
    suspend fun foto(@Path("id") id: String, @Path("evidencia") evidencia: String): ResponseBody

    @POST("api/v1/reportes/{id}/restablecimiento")
    suspend fun restablecer(@Path("id") id: String, @Body cuerpo: NuevoRestablecimientoDto): ReporteDto

    @GET("api/v1/reportes/cercanos")
    suspend fun cercanos(@Query("latitud") latitud: Double, @Query("longitud") longitud: Double): List<ReporteCercanoDto>

    /** 201 si el voto es nuevo; 200 si ya había votado (no cambia nada). */
    @POST("api/v1/reportes/{id}/validaciones")
    suspend fun validar(@Path("id") id: String, @Body cuerpo: NuevaValidacionDto): Response<ValidacionDto>

    @GET("api/v1/cuentas/yo")
    suspend fun yo(): CuentaDto

    @PATCH("api/v1/cuentas/yo")
    suspend fun cambiarYo(@Body cambios: CambiosCuentaPropiaDto): CuentaDto

    @DELETE("api/v1/cuentas/yo")
    suspend fun eliminarYo(): Response<Unit>
}

/**
 * Operaciones públicas. Su cliente HTTP no tiene interceptores: no envía JWT,
 * correlationId ni ningún otro identificador (indispensable en las denuncias
 * anónimas, RNF-SEG4).
 */
interface ApiPublica {
    @POST("api/v1/auth/login")
    suspend fun ingresar(@Body credenciales: CredencialesDto): SesionDto

    @POST("api/v1/auth/registro")
    suspend fun registrar(@Body nuevo: NuevoCiudadanoDto): CuentaDto

    /** Síncrona: la usa el autenticador de OkHttp, que corre en el hilo de la petición. */
    @POST("api/v1/auth/refrescar")
    fun refrescar(@Body solicitud: SolicitudRefrescoDto): Call<SesionDto>

    @POST("api/v1/auth/cerrar-sesion")
    suspend fun cerrarSesion(@Body solicitud: SolicitudRefrescoDto): Response<Unit>

    @POST("api/v1/denuncias")
    suspend fun denunciar(@Body denuncia: NuevaDenunciaDto): Response<ResponseBody>

    @Multipart
    @POST("api/v1/denuncias")
    suspend fun denunciarConFoto(@Part denuncia: MultipartBody.Part, @Part foto: MultipartBody.Part): Response<ResponseBody>
}

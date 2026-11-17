package co.edu.javeriana.servired.app.datos.remoto

import co.edu.javeriana.servired.app.datos.sesion.GestorTokens
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

val jsonServiRed = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

private val base = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private fun retrofit(apiBase: String, cliente: OkHttpClient, json: Json) = Retrofit.Builder()
    .baseUrl(apiBase)
    .client(cliente)
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()

/** Cliente sin interceptores: autenticación y denuncias anónimas. */
fun crearApiPublica(apiBase: String, json: Json = jsonServiRed): ApiPublica =
    retrofit(apiBase, base, json).create(ApiPublica::class.java)

/** Cliente del ciudadano: X-Correlation-Id, JWT y renovación ante 401. */
fun crearApiCiudadano(apiBase: String, gestor: GestorTokens, publica: ApiPublica, json: Json = jsonServiRed): ApiCiudadano {
    val refrescar = refrescador(publica)
    val cliente = base.newBuilder()
        .addInterceptor(InterceptorCorrelacion())
        .addInterceptor(InterceptorToken(gestor, refrescar))
        .authenticator(AutenticadorRenovacion(gestor, refrescar))
        .build()
    return retrofit(apiBase, cliente, json).create(ApiCiudadano::class.java)
}

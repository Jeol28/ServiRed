package co.edu.javeriana.servired.app.datos.remoto

import co.edu.javeriana.servired.app.datos.sesion.GestorTokens
import co.edu.javeriana.servired.app.datos.sesion.Renovacion
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.UUID
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Agrega un X-Correlation-Id nuevo si la petición no trae uno (RNF-OBS1). */
class InterceptorCorrelacion : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header(CABECERA_CORRELACION) != null) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header(CABECERA_CORRELACION, UUID.randomUUID().toString()).build())
    }
}

/**
 * Agrega el JWT. Si aún no hay token de acceso (la app se acaba de abrir) pero
 * sí refresco, renueva antes de enviar.
 */
class InterceptorToken(private val gestor: GestorTokens, private val refrescar: (String) -> Renovacion) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = gestor.acceso ?: if (gestor.hayRefresco()) gestor.renovar(null, refrescar) else null
        val req = chain.request()
        return chain.proceed(if (token == null) req else req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/** Ante un 401 renueva una sola vez y repite la petición (con el mismo correlationId). */
class AutenticadorRenovacion(private val gestor: GestorTokens, private val refrescar: (String) -> Renovacion) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val usado = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        if (response.priorResponse != null) return null
        val nuevo = gestor.renovar(usado, refrescar) ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $nuevo").build()
    }
}

/** Llama a /auth/refrescar y clasifica el resultado para el gestor de tokens. */
fun refrescador(api: ApiPublica): (String) -> Renovacion = { refresco ->
    try {
        val r = api.refrescar(SolicitudRefrescoDto(refresco)).execute()
        val cuerpo = r.body()
        when {
            r.isSuccessful && cuerpo != null -> Renovacion.Lista(cuerpo)
            r.code() in 400..499 -> Renovacion.Rechazada
            else -> Renovacion.Incierta
        }
    } catch (e: UnknownHostException) {
        Renovacion.SinEnviar
    } catch (e: ConnectException) {
        Renovacion.SinEnviar
    } catch (e: IOException) {
        Renovacion.Incierta
    }
}

package co.edu.javeriana.servired.app.datos

import co.edu.javeriana.servired.app.datos.local.DaoPendientes
import co.edu.javeriana.servired.app.datos.local.DaoReportes
import co.edu.javeriana.servired.app.datos.local.PendienteEntidad
import co.edu.javeriana.servired.app.datos.local.ReporteEntidad
import co.edu.javeriana.servired.app.datos.remoto.ApiCiudadano
import co.edu.javeriana.servired.app.datos.remoto.NuevaValidacionDto
import co.edu.javeriana.servired.app.datos.remoto.NuevoReporteDto
import co.edu.javeriana.servired.app.datos.remoto.NuevoRestablecimientoDto
import co.edu.javeriana.servired.app.datos.remoto.ReporteDto
import co.edu.javeriana.servired.app.datos.remoto.conteo
import co.edu.javeriana.servired.app.datos.remoto.dominio
import co.edu.javeriana.servired.app.datos.remoto.dto
import co.edu.javeriana.servired.app.datos.remoto.instante
import co.edu.javeriana.servired.app.datos.remoto.llamar
import co.edu.javeriana.servired.app.datos.remoto.objetoDominio
import co.edu.javeriana.servired.app.datos.remoto.textoFecha
import co.edu.javeriana.servired.app.dominio.ErrorApp
import co.edu.javeriana.servired.app.dominio.EstadoEnvio
import co.edu.javeriana.servired.app.dominio.NuevoReporte
import co.edu.javeriana.servired.app.dominio.ObjetoValidacion
import co.edu.javeriana.servired.app.dominio.ProgramadorEnvio
import co.edu.javeriana.servired.app.dominio.Reglas
import co.edu.javeriana.servired.app.dominio.Reporte
import co.edu.javeriana.servired.app.dominio.ReporteCercano
import co.edu.javeriana.servired.app.dominio.ReportePendiente
import co.edu.javeriana.servired.app.dominio.RepositorioReportes
import co.edu.javeriana.servired.app.dominio.Resultado
import co.edu.javeriana.servired.app.dominio.ResultadoValidacion
import co.edu.javeriana.servired.app.dominio.Sentido
import co.edu.javeriana.servired.app.dominio.TipoError
import co.edu.javeriana.servired.app.dominio.Ubicacion
import co.edu.javeriana.servired.app.dominio.Zona
import co.edu.javeriana.servired.app.dominio.map
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * Reportes del ciudadano, con soporte sin conexión:
 * - "Mis reportes" se lee de Room y se actualiza desde el servidor.
 * - Un reporte nuevo se guarda primero en el teléfono (outbox) con un
 *   correlationId fijo, y luego se envía. Si no hay red, WorkManager lo envía
 *   después; repetir el envío con el mismo correlationId no lo duplica.
 * - Cada foto tiene su propio correlationId derivado del reporte, también
 *   estable entre reintentos.
 */
class RepositorioReportesImpl(
    private val api: ApiCiudadano,
    private val daoReportes: DaoReportes,
    private val daoPendientes: DaoPendientes,
    private val json: Json,
    private val programador: ProgramadorEnvio,
    private val reloj: Clock = Clock.systemUTC(),
) : RepositorioReportes {

    private val envio = Mutex()
    private val listaFotos = ListSerializer(String.serializer())

    override fun misReportes(): Flow<List<Reporte>> = daoReportes.observar().map { lista ->
        lista.mapNotNull { e -> runCatching { json.decodeFromString<ReporteDto>(e.json).dominio() }.getOrNull() }
    }

    override fun pendientes(): Flow<List<ReportePendiente>> = daoPendientes.observar().map { lista -> lista.map { it.dominio() } }

    override suspend fun actualizar(): Resultado<Unit> = llamar {
        val todos = mutableListOf<ReporteDto>()
        var pagina = 0
        do {
            val p = api.misReportes(pagina, TAMANO_PAGINA)
            todos += p.elementos
            pagina++
        } while (todos.size < p.total && p.elementos.isNotEmpty() && pagina < MAX_PAGINAS)
        daoReportes.reemplazar(todos.map { it.entidad() })
    }

    override suspend fun obtener(id: String): Resultado<Reporte> = llamar { api.reporte(id) }
        .also { if (it is Resultado.Exito) guardarEnCache(it.valor) }
        .map { it.dominio() }

    override suspend fun crear(nuevo: NuevoReporte, fotos: List<String>): Resultado<Boolean> {
        Reglas.validarReporte(nuevo, reloj.instant()).firstOrNull()?.let { return Resultado.Fallo(ErrorApp.validacion(it)) }
        if (fotos.size > Reglas.MAX_FOTOS) return Resultado.Fallo(ErrorApp.validacion("Máximo ${Reglas.MAX_FOTOS} fotos."))
        daoPendientes.insertar(
            PendienteEntidad(
                correlationId = UUID.randomUUID().toString(),
                json = json.encodeToString(NuevoReporteDto.serializer(), nuevo.dto()),
                fotos = json.encodeToString(listaFotos, fotos),
                estado = EstadoEnvio.PENDIENTE.name,
                creadoEn = reloj.millis(),
            ),
        )
        val enviado = enviarPendientes()
        if (!enviado) programador.programar()
        return Resultado.Exito(enviado)
    }

    override suspend fun enviarPendientes(): Boolean = envio.withLock {
        var todoListo = true
        for (p in daoPendientes.porEnviar()) {
            if (!enviar(p)) todoListo = false
        }
        todoListo
    }

    /** true si el pendiente quedó resuelto (enviado o marcado como fallido definitivo). */
    private suspend fun enviar(inicial: PendienteEntidad): Boolean {
        var p = inicial.copy(estado = EstadoEnvio.ENVIANDO.name, error = null)
        daoPendientes.actualizar(p)
        if (p.idReporte == null) {
            val cuerpo = json.decodeFromString<NuevoReporteDto>(p.json)
            when (val r = llamar { api.crearReporte(p.correlationId, cuerpo) }) {
                is Resultado.Exito -> {
                    guardarEnCache(r.valor)
                    p = p.copy(idReporte = r.valor.idReporte)
                    daoPendientes.actualizar(p)
                }
                is Resultado.Fallo -> return detener(p, r.error)
            }
        }
        val fotos = json.decodeFromString(listaFotos, p.fotos)
        while (p.fotosEnviadas < fotos.size) {
            val i = p.fotosEnviadas
            val archivo = File(fotos[i])
            if (archivo.exists()) {
                when (val r = llamar { api.adjuntarFoto(correlacionFoto(p.correlationId, i), p.idReporte!!, parteFoto(archivo)) }) {
                    is Resultado.Exito -> guardarEnCache(r.valor)
                    // Una foto rechazada (inválida, demasiado grande o ya hay 3) no bloquea el reporte.
                    is Resultado.Fallo -> if (reintentable(r.error)) return detener(p, r.error)
                }
            }
            p = p.copy(fotosEnviadas = i + 1)
            daoPendientes.actualizar(p)
        }
        daoPendientes.borrar(p.idLocal)
        fotos.forEach { File(it).delete() }
        return true
    }

    private suspend fun detener(p: PendienteEntidad, error: ErrorApp): Boolean {
        return if (reintentable(error)) {
            daoPendientes.actualizar(p.copy(estado = EstadoEnvio.PENDIENTE.name, error = error.mensaje))
            false
        } else {
            daoPendientes.actualizar(p.copy(estado = EstadoEnvio.FALLIDO.name, error = error.mensaje))
            true
        }
    }

    private fun reintentable(e: ErrorApp) = e.tipo in setOf(TipoError.SIN_CONEXION, TipoError.SERVIDOR, TipoError.LIMITE, TipoError.SESION)

    override suspend fun descartarPendiente(idLocal: Long) {
        daoPendientes.buscar(idLocal)?.let { p ->
            json.decodeFromString(listaFotos, p.fotos).forEach { File(it).delete() }
            daoPendientes.borrar(idLocal)
        }
    }

    override suspend fun adjuntarFoto(idReporte: String, ruta: String): Resultado<Reporte> {
        val archivo = File(ruta)
        return llamar { api.adjuntarFoto(UUID.randomUUID().toString(), idReporte, parteFoto(archivo)) }
            .also { if (it is Resultado.Exito) guardarEnCache(it.valor) }
            .map { it.dominio() }
            .also { archivo.delete() }
    }

    override suspend fun foto(idReporte: String, idEvidencia: String): Resultado<ByteArray> =
        llamar { api.foto(idReporte, idEvidencia).use { it.bytes() } }

    override suspend fun restablecer(id: String, fecha: Instant, comentario: String?): Resultado<Reporte> {
        // Se consulta el reporte vigente para validar contra su hora de inicio.
        val actual = when (val r = obtener(id)) {
            is Resultado.Exito -> r.valor
            is Resultado.Fallo -> return r
        }
        Reglas.validarRestablecimiento(fecha, actual.inicioEstimado, reloj.instant(), comentario)
            ?.let { return Resultado.Fallo(ErrorApp.validacion(it)) }
        val cuerpo = NuevoRestablecimientoDto(textoFecha(fecha), comentario?.trim()?.takeIf { it.isNotEmpty() })
        return llamar { api.restablecer(id, cuerpo) }
            .also { if (it is Resultado.Exito) guardarEnCache(it.valor) }
            .map { it.dominio() }
    }

    override suspend fun cercanos(desde: Ubicacion): Resultado<List<ReporteCercano>> =
        llamar { api.cercanos(desde.latitud, desde.longitud) }.map { l -> l.map { it.dominio() } }

    override suspend fun zonasPublicas(): Resultado<List<Zona>> =
        llamar { api.mapaPublico() }.map { m -> m.celdas.map { Zona(Ubicacion(it.latitud, it.longitud), it.reportes) } }

    override suspend fun validar(
        id: String, objeto: ObjetoValidacion, sentido: Sentido, desde: Ubicacion,
    ): Resultado<ResultadoValidacion> = llamar {
        val r = api.validar(id, NuevaValidacionDto(objeto.name, sentido.name, desde.dto()))
        val cuerpo = r.body()
        if (!r.isSuccessful || cuerpo == null) throw retrofit2.HttpException(r)
        ResultadoValidacion(cuerpo.objetoDominio(), cuerpo.conteo(), nueva = r.code() == 201)
    }.let { if (it is Resultado.Fallo && it.error.tipo == TipoError.CONFLICTO) Resultado.Fallo(motivoValidacion(it.error)) else it }

    private suspend fun guardarEnCache(r: ReporteDto) = daoReportes.guardar(listOf(r.entidad()))

    private fun ReporteDto.entidad() =
        ReporteEntidad(idReporte, json.encodeToString(ReporteDto.serializer(), this), instante(fechaRegistro).toEpochMilli())

    private fun PendienteEntidad.dominio() = ReportePendiente(
        idLocal = idLocal,
        correlationId = correlationId,
        datos = this@RepositorioReportesImpl.json.decodeFromString<NuevoReporteDto>(this.json).dominio(),
        fotos = this@RepositorioReportesImpl.json.decodeFromString(listaFotos, fotos),
        estado = EstadoEnvio.valueOf(estado),
        error = error,
        creadoEn = Instant.ofEpochMilli(creadoEn),
        idReporte = idReporte,
    )

    companion object {
        private const val TAMANO_PAGINA = 50
        private const val MAX_PAGINAS = 10
        private val JPEG = "image/jpeg".toMediaType()

        /** Mismo reporte y misma foto -> mismo correlationId en cada reintento. */
        fun correlacionFoto(correlacionReporte: String, indice: Int): String =
            UUID.nameUUIDFromBytes("$correlacionReporte/foto/$indice".toByteArray()).toString()

        fun parteFoto(archivo: File): MultipartBody.Part =
            MultipartBody.Part.createFormData("foto", "foto.jpg", archivo.asRequestBody(JPEG))

        fun motivoValidacion(e: ErrorApp): ErrorApp = e.copy(
            mensaje = when (e.motivo) {
                "AUTOR" -> "No puede validar su propio reporte."
                "LEJOS" -> "Debe estar a menos de 1 km del reporte para validarlo."
                "CERRADO" -> "El reporte ya está cerrado."
                "SIN_RESTABLECIMIENTO" -> "Aún nadie reportó que el servicio volvió."
                else -> e.mensaje
            },
        )
    }
}

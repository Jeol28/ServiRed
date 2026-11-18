package co.edu.javeriana.servired.app.dominio

import java.time.Instant

/*
 * Modelos del dominio de la app del ciudadano. Reflejan el contrato público
 * (servired-api.v1.yaml) sin depender de Android ni de la capa de red.
 */

enum class TipoServicio(val etiqueta: String) {
    ENERGIA("Energía"), GAS("Gas"), ACUEDUCTO("Acueducto"), ALCANTARILLADO("Alcantarillado"), ASEO("Aseo"),
}

enum class Magnitud(val etiqueta: String) {
    VIVIENDA("Solo mi vivienda"), CUADRA("La cuadra"), BARRIO("El barrio"), SECTOR("Un sector amplio"),
}

enum class EstadoReporte(val etiqueta: String) {
    RECIBIDO("Recibido"), EN_ATENCION("En atención"), RESTABLECIDO("Restablecido"), CERRADO("Cerrado"),
}

enum class ObjetoValidacion { CORTE, RESTABLECIMIENTO }

enum class Sentido { CONFIRMA, CONTROVIERTE }

data class Ubicacion(val latitud: Double, val longitud: Double)

/** Conteo de validaciones comunitarias. Validado con 3 confirmaciones netas (ADR-08). */
data class Conteo(val confirmaciones: Int, val controversias: Int, val netas: Int) {
    val validado: Boolean get() = netas >= Reglas.CONFIRMACIONES_PARA_VALIDAR
}

data class EntradaHistoria(val estado: EstadoReporte, val fecha: Instant, val detalle: String?)

data class Evidencia(val id: String, val tipoContenido: String, val tamanoBytes: Long, val creadaEn: Instant)

data class Reporte(
    val id: String,
    val tipoServicio: TipoServicio,
    val ubicacion: Ubicacion,
    val codigoMunicipio: String,
    val nombreMunicipio: String?,
    val inicioEstimado: Instant,
    val magnitud: Magnitud,
    val descripcion: String?,
    val estado: EstadoReporte,
    val fechaRegistro: Instant,
    val fechaRestablecimiento: Instant?,
    val duracionMinutos: Int?,
    val validacionCorte: Conteo?,
    val validacionRestablecimiento: Conteo?,
    val evidencias: List<Evidencia>,
    val historia: List<EntradaHistoria>,
) {
    val puedeRestablecer: Boolean get() = fechaRestablecimiento == null && estado != EstadoReporte.CERRADO
    val puedeAdjuntarFoto: Boolean get() = evidencias.size < Reglas.MAX_FOTOS && estado != EstadoReporte.CERRADO
}

/** Reporte de otro ciudadano a menos de 1 km: sin autor ni descripción. */
data class ReporteCercano(
    val id: String,
    val tipoServicio: TipoServicio,
    val magnitud: Magnitud,
    val estado: EstadoReporte,
    val inicioEstimado: Instant,
    val ubicacion: Ubicacion,
    val distanciaMetros: Int,
    val corte: Conteo?,
    val restablecimiento: Conteo?,
)

/** Zona de ~1 km con cortes reportados, agregada y anónima (k ≥ 3): la vista nacional del mapa. */
data class Zona(val ubicacion: Ubicacion, val reportes: Int)

data class ResultadoValidacion(val objeto: ObjetoValidacion, val conteo: Conteo, val nueva: Boolean)

data class Cuenta(val id: String, val correo: String, val nombre: String?, val rol: String)

data class NuevoReporte(
    val tipoServicio: TipoServicio,
    val ubicacion: Ubicacion,
    val inicioEstimado: Instant,
    val magnitud: Magnitud,
    val descripcion: String?,
)

data class NuevaDenuncia(val tipoServicio: TipoServicio, val ubicacion: Ubicacion, val descripcion: String)

enum class EstadoEnvio { PENDIENTE, ENVIANDO, FALLIDO }

/**
 * Reporte guardado en el teléfono hasta que el servidor lo confirme. Su
 * `correlationId` se fija al crearlo y se reutiliza en cada reintento: es la
 * clave de idempotencia, así que reintentar nunca duplica el reporte.
 */
data class ReportePendiente(
    val idLocal: Long,
    val correlationId: String,
    val datos: NuevoReporte,
    val fotos: List<String>,
    val estado: EstadoEnvio,
    val error: String?,
    val creadoEn: Instant,
    /** Ya creado en el servidor; faltan fotos por subir. */
    val idReporte: String?,
)

sealed interface EstadoSesion {
    data object Desconocida : EstadoSesion
    data object Anonima : EstadoSesion
    data class Autenticada(val idCuenta: String, val rol: String, val nombre: String?) : EstadoSesion
}

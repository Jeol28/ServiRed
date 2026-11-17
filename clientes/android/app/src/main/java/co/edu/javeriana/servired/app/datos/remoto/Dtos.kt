package co.edu.javeriana.servired.app.datos.remoto

import kotlinx.serialization.Serializable

/* DTO del contrato público v1. Las fechas viajan como texto ISO-8601 con zona. */

@Serializable data class UbicacionDto(val latitud: Double, val longitud: Double)

@Serializable data class NuevoReporteDto(
    val tipoServicio: String,
    val ubicacion: UbicacionDto,
    val inicioEstimado: String,
    val magnitud: String,
    val descripcion: String? = null,
)

@Serializable data class ConteoDto(val confirmaciones: Int, val controversias: Int, val confirmacionesNetas: Int)

@Serializable data class ValidacionesDto(val corte: ConteoDto? = null, val restablecimiento: ConteoDto? = null)

@Serializable data class EvidenciaDto(
    val idEvidencia: String,
    val tipoContenido: String,
    val tamanoBytes: Long,
    val creadaEn: String,
)

@Serializable data class EntradaHistoriaDto(val estado: String, val fecha: String, val detalle: String? = null)

@Serializable data class ReporteDto(
    val idReporte: String,
    val tipoServicio: String,
    val ubicacion: UbicacionDto,
    val codigoMunicipio: String,
    val nombreMunicipio: String? = null,
    val inicioEstimado: String,
    val magnitud: String,
    val descripcion: String? = null,
    val estado: String,
    val fechaRegistro: String,
    val fechaRestablecimiento: String? = null,
    val duracionMinutos: Int? = null,
    val validacion: ValidacionesDto? = null,
    val evidencias: List<EvidenciaDto> = emptyList(),
    val historia: List<EntradaHistoriaDto> = emptyList(),
)

@Serializable data class PaginaReportesDto(val elementos: List<ReporteDto>, val total: Int, val pagina: Int, val tamano: Int)

@Serializable data class ReporteCercanoDto(
    val idReporte: String,
    val tipoServicio: String,
    val magnitud: String,
    val estado: String,
    val inicioEstimado: String,
    val ubicacion: UbicacionDto,
    val distanciaMetros: Int,
    val validacionCorte: ConteoDto? = null,
    val validacionRestablecimiento: ConteoDto? = null,
)

@Serializable data class NuevaValidacionDto(val objeto: String, val sentido: String, val ubicacion: UbicacionDto)

@Serializable data class ValidacionDto(
    val idReporte: String,
    val objeto: String,
    val confirmaciones: Int,
    val controversias: Int,
    val confirmacionesNetas: Int,
    val validado: Boolean,
)

@Serializable data class NuevoRestablecimientoDto(val fechaRestablecimiento: String, val comentario: String? = null)

@Serializable data class NuevaDenunciaDto(val tipoServicio: String, val ubicacion: UbicacionDto, val descripcion: String)

@Serializable data class CredencialesDto(val correo: String, val clave: String)

@Serializable data class NuevoCiudadanoDto(
    val correo: String,
    val clave: String,
    val nombre: String? = null,
    val aceptaTratamientoDatos: Boolean,
)

@Serializable data class ResumenCuentaDto(val idCuenta: String, val rol: String, val nombre: String? = null)

@Serializable data class SesionDto(
    val tokenAcceso: String,
    val tipoToken: String,
    val expiraEn: Long,
    val tokenRefresco: String,
    val cuenta: ResumenCuentaDto,
)

@Serializable data class SolicitudRefrescoDto(val tokenRefresco: String)

@Serializable data class CuentaDto(
    val idCuenta: String,
    val correo: String,
    val rol: String,
    val nombre: String? = null,
    val activa: Boolean = true,
)

@Serializable data class CambiosCuentaPropiaDto(
    val nombre: String? = null,
    val claveActual: String? = null,
    val claveNueva: String? = null,
)

@Serializable data class ErrorCampoDto(val campo: String = "", val mensaje: String = "")

/** RFC 9457 Problem Details. */
@Serializable data class ProblemaDto(
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val correlationId: String? = null,
    val motivo: String? = null,
    val errores: List<ErrorCampoDto>? = null,
)

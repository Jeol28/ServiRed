package co.edu.javeriana.servired.app.datos.remoto

import co.edu.javeriana.servired.app.dominio.Conteo
import co.edu.javeriana.servired.app.dominio.Cuenta
import co.edu.javeriana.servired.app.dominio.EntradaHistoria
import co.edu.javeriana.servired.app.dominio.EstadoReporte
import co.edu.javeriana.servired.app.dominio.Evidencia
import co.edu.javeriana.servired.app.dominio.Magnitud
import co.edu.javeriana.servired.app.dominio.NuevaDenuncia
import co.edu.javeriana.servired.app.dominio.NuevoReporte
import co.edu.javeriana.servired.app.dominio.ObjetoValidacion
import co.edu.javeriana.servired.app.dominio.Reporte
import co.edu.javeriana.servired.app.dominio.ReporteCercano
import co.edu.javeriana.servired.app.dominio.TipoServicio
import co.edu.javeriana.servired.app.dominio.Ubicacion
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Acepta "Z" u offsets como "-05:00". */
fun instante(texto: String): Instant = OffsetDateTime.parse(texto).toInstant()

/** Fecha con la zona del teléfono (RNF-DAT1: siempre con zona horaria). */
fun textoFecha(instante: Instant, zona: ZoneId = ZoneId.systemDefault()): String =
    OffsetDateTime.ofInstant(instante, zona).toString()

fun UbicacionDto.dominio() = Ubicacion(latitud, longitud)
fun Ubicacion.dto() = UbicacionDto(latitud, longitud)
fun ConteoDto.dominio() = Conteo(confirmaciones, controversias, confirmacionesNetas)

fun ReporteDto.dominio() = Reporte(
    id = idReporte,
    tipoServicio = TipoServicio.valueOf(tipoServicio),
    ubicacion = ubicacion.dominio(),
    codigoMunicipio = codigoMunicipio,
    nombreMunicipio = nombreMunicipio,
    inicioEstimado = instante(inicioEstimado),
    magnitud = Magnitud.valueOf(magnitud),
    descripcion = descripcion,
    estado = EstadoReporte.valueOf(estado),
    fechaRegistro = instante(fechaRegistro),
    fechaRestablecimiento = fechaRestablecimiento?.let(::instante),
    duracionMinutos = duracionMinutos,
    validacionCorte = validacion?.corte?.dominio(),
    validacionRestablecimiento = validacion?.restablecimiento?.dominio(),
    evidencias = evidencias.map { Evidencia(it.idEvidencia, it.tipoContenido, it.tamanoBytes, instante(it.creadaEn)) },
    historia = historia.map { EntradaHistoria(EstadoReporte.valueOf(it.estado), instante(it.fecha), it.detalle) },
)

fun ReporteCercanoDto.dominio() = ReporteCercano(
    id = idReporte,
    tipoServicio = TipoServicio.valueOf(tipoServicio),
    magnitud = Magnitud.valueOf(magnitud),
    estado = EstadoReporte.valueOf(estado),
    inicioEstimado = instante(inicioEstimado),
    ubicacion = ubicacion.dominio(),
    distanciaMetros = distanciaMetros,
    corte = validacionCorte?.dominio(),
    restablecimiento = validacionRestablecimiento?.dominio(),
)

fun ValidacionDto.conteo() = Conteo(confirmaciones, controversias, confirmacionesNetas)
fun ValidacionDto.objetoDominio() = ObjetoValidacion.valueOf(objeto)

fun NuevoReporte.dto() = NuevoReporteDto(
    tipoServicio = tipoServicio.name,
    ubicacion = ubicacion.dto(),
    inicioEstimado = textoFecha(inicioEstimado),
    magnitud = magnitud.name,
    descripcion = descripcion?.trim()?.takeIf { it.isNotEmpty() },
)

fun NuevoReporteDto.dominio() = NuevoReporte(
    tipoServicio = TipoServicio.valueOf(tipoServicio),
    ubicacion = ubicacion.dominio(),
    inicioEstimado = instante(inicioEstimado),
    magnitud = Magnitud.valueOf(magnitud),
    descripcion = descripcion,
)

fun NuevaDenuncia.dto() = NuevaDenunciaDto(tipoServicio.name, ubicacion.dto(), descripcion.trim())

fun CuentaDto.dominio() = Cuenta(idCuenta, correo, nombre, rol)

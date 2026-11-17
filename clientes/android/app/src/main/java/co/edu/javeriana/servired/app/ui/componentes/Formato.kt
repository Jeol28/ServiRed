package co.edu.javeriana.servired.app.ui.componentes

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val FECHA = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withLocale(Locale.forLanguageTag("es-CO"))

fun fechaHora(i: Instant?, zona: ZoneId = ZoneId.systemDefault()): String = i?.let { FECHA.withZone(zona).format(it) } ?: "—"

fun duracion(minutos: Long?): String {
    if (minutos == null) return "—"
    val d = minutos / 1440
    val h = (minutos % 1440) / 60
    val m = minutos % 60
    return listOfNotNull(
        if (d > 0) "$d d" else null,
        if (h > 0) "$h h" else null,
        if (m > 0 || (d == 0L && h == 0L)) "$m min" else null,
    ).joinToString(" ")
}

fun distancia(metros: Int): String = if (metros < 1000) "a $metros m" else "a ${"%.1f".format(metros / 1000.0)} km"

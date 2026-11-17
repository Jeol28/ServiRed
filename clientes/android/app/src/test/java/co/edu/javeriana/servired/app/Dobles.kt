package co.edu.javeriana.servired.app

import co.edu.javeriana.servired.app.datos.local.DaoPendientes
import co.edu.javeriana.servired.app.datos.local.DaoReportes
import co.edu.javeriana.servired.app.datos.local.PendienteEntidad
import co.edu.javeriana.servired.app.datos.local.ReporteEntidad
import co.edu.javeriana.servired.app.datos.sesion.CofreTokens
import co.edu.javeriana.servired.app.dominio.ProveedorUbicacion
import co.edu.javeriana.servired.app.dominio.Ubicacion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class CofreMemoria(var refresco: String? = null, var cuenta: String? = null) : CofreTokens {
    val historial = mutableListOf<String>()
    override fun leer() = refresco
    override fun guardar(refresco: String) { this.refresco = refresco; historial += "guardar:$refresco" }
    override fun borrar() { refresco = null; cuenta = null; historial += "borrar" }
    override fun leerCuenta() = cuenta
    override fun guardarCuenta(cuenta: String) { this.cuenta = cuenta }
}

class DaoReportesMemoria : DaoReportes {
    val datos = MutableStateFlow<Map<String, ReporteEntidad>>(emptyMap())
    override fun observar(): Flow<List<ReporteEntidad>> = datos.map { m -> m.values.sortedByDescending { it.fechaRegistro } }
    override suspend fun guardar(reportes: List<ReporteEntidad>) = datos.update { m -> m + reportes.associateBy { it.id } }
    override suspend fun borrarTodo() = datos.update { emptyMap() }
}

class DaoPendientesMemoria : DaoPendientes {
    val datos = MutableStateFlow<Map<Long, PendienteEntidad>>(emptyMap())
    private var siguiente = 1L
    override fun observar(): Flow<List<PendienteEntidad>> = datos.map { it.values.sortedBy { p -> p.creadoEn } }
    override suspend fun porEnviar() = datos.value.values.filter { it.estado != "FALLIDO" }.sortedBy { it.creadoEn }
    override suspend fun buscar(idLocal: Long) = datos.value[idLocal]
    override suspend fun insertar(p: PendienteEntidad): Long {
        val id = siguiente++
        datos.update { it + (id to p.copy(idLocal = id)) }
        return id
    }
    override suspend fun actualizar(p: PendienteEntidad) = datos.update { it + (p.idLocal to p) }
    override suspend fun borrar(idLocal: Long) = datos.update { it - idLocal }
    override suspend fun borrarTodo() = datos.update { emptyMap() }
}

class UbicacionFija(var u: Ubicacion? = Ubicacion(4.6287, -74.0648)) : ProveedorUbicacion {
    override suspend fun actual() = u
}

const val SESION_JSON = """{"tokenAcceso":"%s","tipoToken":"Bearer","expiraEn":900,"tokenRefresco":"%s",
  "cuenta":{"idCuenta":"c-1","rol":"CIUDADANO","nombre":"Ana"}}"""

fun sesionJson(acceso: String, refresco: String) = SESION_JSON.format(acceso, refresco)

fun reporteJson(id: String = "r-1", estado: String = "RECIBIDO") = """
{"idReporte":"$id","tipoServicio":"ENERGIA","ubicacion":{"latitud":4.6287,"longitud":-74.0648},
 "codigoMunicipio":"11001","nombreMunicipio":"BOGOTÁ, D.C.","inicioEstimado":"2026-11-17T07:00:00-05:00",
 "magnitud":"CUADRA","estado":"$estado","fechaRegistro":"2026-11-17T12:05:00Z",
 "validacion":{"corte":{"confirmaciones":1,"controversias":0,"confirmacionesNetas":1}},
 "evidencias":[],"historia":[{"estado":"RECIBIDO","fecha":"2026-11-17T12:05:00Z","detalle":"Reporte recibido"}]}
"""

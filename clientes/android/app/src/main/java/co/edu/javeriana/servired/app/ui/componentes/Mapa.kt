package co.edu.javeriana.servired.app.ui.componentes

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.edu.javeriana.servired.app.dominio.Ubicacion
import co.edu.javeriana.servired.app.dominio.Zona
import co.edu.javeriana.servired.app.ui.mapa.iconoMarcador
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Marca del mapa. Con `icono` se dibuja un círculo del color con ese símbolo;
 * `propio` agrega un anillo oscuro (reportes del propio ciudadano).
 */
data class MarcaMapa(
    val id: String,
    val ubicacion: Ubicacion,
    val color: Color,
    val icono: String? = null,
    val propio: Boolean = false,
)

/** Teselas raster de OpenStreetMap, con su atribución. */
private const val ESTILO_OSM = """
{"version":8,
 "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
   "tileSize":256,"maxzoom":19,"attribution":"© colaboradores de OpenStreetMap"}},
 "layers":[{"id":"osm","type":"raster","source":"osm"}]}
"""
private const val FUENTE_ZONAS = "zonas"
private const val FUENTE_MARCAS = "marcas"
private const val FUENTE_USUARIO = "usuario"
private const val FUENTE_SELECCION = "seleccion"
private const val CAPA_MARCAS = "capa-marcas"

/**
 * Mapa MapLibre con OpenStreetMap:
 * - `zonas`: capa nacional anónima, visible solo con el mapa alejado.
 * - `marcas`: íconos tocables (`alTocarMarca`).
 * - `usuario`: punto azul de la ubicación del ciudadano.
 * - `seleccion`: punto elegido tocando el mapa (`alElegir`).
 * - `alMoverCentro`: centro del mapa cada vez que se detiene (para ubicar un reporte).
 * - `recentrar`: al cambiar, vuelve a `centro` aunque sea el mismo punto.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapaOsm(
    modifier: Modifier = Modifier,
    marcas: List<MarcaMapa> = emptyList(),
    zonas: List<Zona> = emptyList(),
    usuario: Ubicacion? = null,
    seleccion: Ubicacion? = null,
    centro: Ubicacion? = seleccion,
    zoom: Double = 15.0,
    recentrar: Int = 0,
    alElegir: ((Ubicacion) -> Unit)? = null,
    alTocarMarca: ((String) -> Unit)? = null,
    alMoverCentro: ((Ubicacion) -> Unit)? = null,
) {
    val contexto = LocalContext.current
    val densidad = LocalDensity.current.density
    val vista = remember {
        MapView(contexto).apply {
            onCreate(null)
            // El mapa recibe los gestos aunque esté dentro de una columna desplazable.
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
    var mapa by remember { mutableStateOf<MapLibreMap?>(null) }
    var estilo by remember { mutableStateOf<Style?>(null) }
    val elegir by rememberUpdatedState(alElegir)
    val tocarMarca by rememberUpdatedState(alTocarMarca)
    val moverCentro by rememberUpdatedState(alMoverCentro)

    val ciclo = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(ciclo) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_START -> vista.onStart()
                Lifecycle.Event.ON_RESUME -> vista.onResume()
                Lifecycle.Event.ON_PAUSE -> vista.onPause()
                Lifecycle.Event.ON_STOP -> vista.onStop()
                else -> Unit
            }
        }
        ciclo.addObserver(observador)
        onDispose {
            ciclo.removeObserver(observador)
            if (ciclo.currentState.isAtLeast(Lifecycle.State.RESUMED)) vista.onPause()
            if (ciclo.currentState.isAtLeast(Lifecycle.State.STARTED)) vista.onStop()
            vista.onDestroy()
        }
    }

    LaunchedEffect(vista) {
        vista.getMapAsync { m ->
            m.cameraPosition = CameraPosition.Builder().target(LatLng(4.6, -74.1)).zoom(5.0).build()
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.setStyle(Style.Builder().fromJson(ESTILO_OSM)) { s ->
                // Zonas nacionales: solo con el mapa alejado (en el barrio estorbarían).
                s.addSource(GeoJsonSource(FUENTE_ZONAS))
                s.addLayer(
                    CircleLayer("capa-zonas", FUENTE_ZONAS).withProperties(
                        circleRadius(
                            Expression.interpolate(
                                Expression.linear(), Expression.get("reportes"),
                                Expression.stop(3, 10f), Expression.stop(30, 26f),
                            ),
                        ),
                        circleColor("#DC2626"), circleOpacity(0.28f),
                        circleStrokeColor("#DC2626"), circleStrokeWidth(1f),
                    ).apply { maxZoom = 12.5f },
                )
                s.addSource(GeoJsonSource(FUENTE_USUARIO))
                s.addLayer(
                    CircleLayer("capa-usuario-halo", FUENTE_USUARIO)
                        .withProperties(circleRadius(20f), circleColor("#2563EB"), circleOpacity(0.18f)),
                )
                s.addLayer(
                    CircleLayer("capa-usuario", FUENTE_USUARIO).withProperties(
                        circleRadius(8f), circleColor("#2563EB"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
                    ),
                )
                s.addSource(GeoJsonSource(FUENTE_SELECCION))
                s.addLayer(
                    CircleLayer("capa-seleccion", FUENTE_SELECCION).withProperties(
                        circleRadius(11f), circleColor("#0B5394"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
                    ),
                )
                s.addSource(GeoJsonSource(FUENTE_MARCAS))
                s.addLayer(
                    SymbolLayer(CAPA_MARCAS, FUENTE_MARCAS).withProperties(
                        iconImage(Expression.get("icono")), iconAllowOverlap(true), iconIgnorePlacement(true),
                    ),
                )
                estilo = s
            }
            m.addOnMapClickListener { punto ->
                val pantalla = m.projection.toScreenLocation(punto)
                val tocada = m.queryRenderedFeatures(pantalla, CAPA_MARCAS).firstOrNull()?.getStringProperty("id")
                when {
                    tocada != null && tocarMarca != null -> tocarMarca?.invoke(tocada)
                    elegir != null -> elegir?.invoke(Ubicacion(redondear(punto.latitude), redondear(punto.longitude)))
                    else -> return@addOnMapClickListener false
                }
                true
            }
            m.addOnCameraIdleListener {
                m.cameraPosition.target?.let { c -> moverCentro?.invoke(Ubicacion(redondear(c.latitude), redondear(c.longitude))) }
            }
            mapa = m
        }
    }

    LaunchedEffect(estilo, marcas) {
        val s = estilo ?: return@LaunchedEffect
        val caracteristicas = marcas.map { m ->
            val clave = "icono-${m.icono}-${m.color.toArgb()}-${m.propio}"
            if (s.getImage(clave) == null) s.addImage(clave, iconoMarcador(m.icono, m.color, m.propio, densidad))
            Feature.fromGeometry(Point.fromLngLat(m.ubicacion.longitud, m.ubicacion.latitud)).apply {
                addStringProperty("id", m.id)
                addStringProperty("icono", clave)
            }
        }
        s.getSourceAs<GeoJsonSource>(FUENTE_MARCAS)?.setGeoJson(FeatureCollection.fromFeatures(caracteristicas))
    }
    LaunchedEffect(estilo, zonas) {
        estilo?.getSourceAs<GeoJsonSource>(FUENTE_ZONAS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                zonas.map { z ->
                    Feature.fromGeometry(Point.fromLngLat(z.ubicacion.longitud, z.ubicacion.latitud))
                        .apply { addNumberProperty("reportes", z.reportes) }
                },
            ),
        )
    }
    LaunchedEffect(estilo, usuario) { estilo?.getSourceAs<GeoJsonSource>(FUENTE_USUARIO)?.setGeoJson(punto(usuario)) }
    LaunchedEffect(estilo, seleccion) { estilo?.getSourceAs<GeoJsonSource>(FUENTE_SELECCION)?.setGeoJson(punto(seleccion)) }
    LaunchedEffect(mapa, centro, recentrar) {
        val c = centro ?: return@LaunchedEffect
        mapa?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.latitud, c.longitud), zoom))
    }

    AndroidView(factory = { vista }, modifier = modifier)
}

private fun punto(u: Ubicacion?) = FeatureCollection.fromFeatures(
    listOfNotNull(u?.let { Feature.fromGeometry(Point.fromLngLat(it.longitud, it.latitud)) }),
)

private fun redondear(v: Double) = Math.round(v * 1e5) / 1e5

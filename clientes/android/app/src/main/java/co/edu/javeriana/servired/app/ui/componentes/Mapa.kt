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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.edu.javeriana.servired.app.dominio.Ubicacion
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

data class MarcaMapa(val id: String, val ubicacion: Ubicacion, val color: Color)

/** Teselas raster de OpenStreetMap, con su atribución. */
private const val ESTILO_OSM = """
{"version":8,
 "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
   "tileSize":256,"maxzoom":19,"attribution":"© colaboradores de OpenStreetMap"}},
 "layers":[{"id":"osm","type":"raster","source":"osm"}]}
"""
private const val FUENTE_MARCAS = "marcas"
private const val FUENTE_SELECCION = "seleccion"
private const val CAPA_MARCAS = "capa-marcas"

/**
 * Mapa MapLibre con OpenStreetMap. Dibuja marcas circulares y, si se pasa
 * `alElegir`, permite marcar una ubicación tocando el mapa.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapaOsm(
    modifier: Modifier = Modifier,
    marcas: List<MarcaMapa> = emptyList(),
    seleccion: Ubicacion? = null,
    centro: Ubicacion? = seleccion,
    zoom: Double = 15.0,
    alElegir: ((Ubicacion) -> Unit)? = null,
    alTocarMarca: ((String) -> Unit)? = null,
) {
    val contexto = LocalContext.current
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
            m.setStyle(Style.Builder().fromJson(ESTILO_OSM)) { s ->
                s.addSource(GeoJsonSource(FUENTE_MARCAS))
                s.addLayer(
                    CircleLayer(CAPA_MARCAS, FUENTE_MARCAS).withProperties(
                        circleRadius(9f), circleColor(Expression.get("color")),
                        circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                    ),
                )
                s.addSource(GeoJsonSource(FUENTE_SELECCION))
                s.addLayer(
                    CircleLayer("capa-seleccion", FUENTE_SELECCION).withProperties(
                        circleRadius(11f), circleColor("#0B5394"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
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
            mapa = m
        }
    }

    LaunchedEffect(estilo, marcas) {
        estilo?.getSourceAs<GeoJsonSource>(FUENTE_MARCAS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                marcas.map { m ->
                    Feature.fromGeometry(Point.fromLngLat(m.ubicacion.longitud, m.ubicacion.latitud)).apply {
                        addStringProperty("id", m.id)
                        addStringProperty("color", "#%06X".format(0xFFFFFF and m.color.toArgb()))
                    }
                },
            ),
        )
    }
    LaunchedEffect(estilo, seleccion) {
        estilo?.getSourceAs<GeoJsonSource>(FUENTE_SELECCION)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(seleccion?.let { Feature.fromGeometry(Point.fromLngLat(it.longitud, it.latitud)) }),
            ),
        )
    }
    LaunchedEffect(mapa, centro) {
        val c = centro ?: return@LaunchedEffect
        mapa?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.latitud, c.longitud), zoom))
    }

    AndroidView(factory = { vista }, modifier = modifier)
}

private fun redondear(v: Double) = Math.round(v * 1e5) / 1e5

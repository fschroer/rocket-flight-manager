package com.steampigeon.flightmanager.ui

// ---------------------------------------------------------------------------
// MapLibre ⇄ Compose adapter layer for FlightMapScreen.
//
// Replaces Google's maps-compose (GoogleMap { Marker/Circle/Polyline },
// CameraPositionState) with the MapLibre core SDK. The intricate camera-framing
// logic in MapCameraController is SDK-agnostic and stays almost unchanged; this
// file provides the two seams it needs:
//   1. MapLibreCameraState — a Compose-observable camera holder that mirrors
//      Google's CameraPositionState (read position, observe move reason, write
//      position to move the map).
//   2. MapLibreMapView — an AndroidView-hosted MapView that renders the satellite
//      style and draws the rocket marker / accuracy ring / flight path as GeoJSON
//      style layers (the imperative equivalent of the old declarative children).
// ---------------------------------------------------------------------------

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.cos

// The gesture reason constant, surfaced so MapCameraController can compare against it
// exactly as it compared against Google's CameraMoveStartedReason.GESTURE.
const val REASON_GESTURE = MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE

/**
 * A lightweight mirror of Google CameraPosition's fields (all Float, like Google's)
 * so the camera-controller math needs no numeric-type churn. Converted to/from
 * MapLibre's Double-typed [CameraPosition] only at the map boundary.
 */
data class CamPos(
    val target: LatLng,
    val zoom: Float,
    val tilt: Float,
    val bearing: Float,
)

private fun CamPos.toMapLibre(): CameraPosition =
    CameraPosition.Builder()
        .target(target)
        .zoom(zoom.toDouble())
        .tilt(tilt.toDouble())
        .bearing(bearing.toDouble())
        .build()

private fun CameraPosition.toCamPos(): CamPos =
    CamPos(target ?: LatLng(0.0, 0.0), zoom.toFloat(), tilt.toFloat(), bearing.toFloat())

/**
 * Compose-observable camera state, the MapLibre analogue of maps-compose's
 * `CameraPositionState`. Reading [position] observes the live camera; assigning
 * [position] moves the map. [moveStartedReason] mirrors Google's
 * `cameraMoveStartedReason` (compare against [REASON_GESTURE]).
 */
@Stable
class MapLibreCameraState(initial: CamPos) {
    internal var map: MapLibreMap? = null

    private var _position by mutableStateOf(initial)
    private var _reason by mutableIntStateOf(-1)

    var moveStartedReason: Int
        get() = _reason
        internal set(value) { _reason = value }

    var position: CamPos
        get() = _position
        set(value) {
            _position = value
            // Move the map to match. moveCamera is instant (no animation), matching
            // the per-frame Kalman driver's expectation; programmatic moves report a
            // non-gesture reason so they never trip the user-gesture backoff.
            map?.moveCamera(CameraUpdateFactory.newCameraPosition(value.toMapLibre()))
        }

    /** Called from the map's own camera listeners — updates state WITHOUT re-moving the map. */
    internal fun syncFromMap(p: CameraPosition) { _position = p.toCamPos() }
}

/** Reads the bundled satellite style JSON (same asset the offline pack renders from). */
fun loadSatelliteStyleJson(context: Context): String =
    context.assets.open("satellite_style.json").bufferedReader().use { it.readText() }

// Layer / source ids
private const val SRC_ACCURACY = "accuracy-src"
private const val SRC_PATH = "path-src"
private const val SRC_ROCKET = "rocket-src"
private const val LYR_ACCURACY_FILL = "accuracy-fill"
private const val LYR_ACCURACY_LINE = "accuracy-line"
private const val LYR_PATH = "path-line"
private const val LYR_ROCKET = "rocket-dot"

private const val COLOR_GREEN = 0xFF00FF00.toInt()
private const val COLOR_RED = 0xFFFF0000.toInt()
private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
private const val COLOR_PATH = 0xFFFF6600.toInt()

/**
 * MapLibre map hosting the satellite style, with the rocket marker, GPS-accuracy ring,
 * and flight-path polyline drawn as GeoJSON style layers. Compose overlays (compass,
 * scale bar, gauges) sit on top exactly as they did over GoogleMap.
 *
 * @param rocketFresh drives green (recent pre-launch message) vs red styling, mirroring
 *        the old BitmapDescriptorFactory HUE_GREEN/HUE_RED marker + circle colors.
 */
@SuppressLint("MissingPermission") // guarded by an explicit ACCESS_FINE_LOCATION check
@Composable
fun MapLibreMapView(
    modifier: Modifier,
    styleJson: String,
    cameraState: MapLibreCameraState,
    rocketLatLng: LatLng,
    rocketFresh: Boolean,
    accuracyRadiusM: Double,
    flightPath: List<LatLng>,
    userLocation: Location?,
    onMapLoaded: () -> Unit,
    onMapClick: () -> Unit,
    onMapReady: (MapLibreMap) -> Unit,
    onSizeChanged: (IntSize) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.onSizeChanged(onSizeChanged),
        factory = { ctx ->
            MapView(ctx).also { mv ->
                mapView = mv
                mv.onCreate(null)
                mv.getMapAsync { map ->
                    map.uiSettings.apply {
                        isCompassEnabled = false        // app draws its own compass
                        isTiltGesturesEnabled = false   // matches old MapUiSettings
                        isRotateGesturesEnabled = true
                        isZoomGesturesEnabled = true
                        isScrollGesturesEnabled = true
                        isLogoEnabled = false           // avoid overlap w/ app's bottom-left overlays
                        isAttributionEnabled = false
                    }
                    map.setMaxPitchPreference(85.0)     // allow the 60–67.5° 3D tilt range
                    map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                        setupContentLayers(style)
                        // Blue "my location" dot (the old isMyLocationEnabled). We feed it the
                        // app's existing fused-location fixes via forceLocationUpdate rather than
                        // spinning up a second engine; camera mode NONE leaves framing to
                        // MapCameraController.
                        if (ContextCompat.checkSelfPermission(mv.context, Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            map.locationComponent.activateLocationComponent(
                                LocationComponentActivationOptions.builder(mv.context, style)
                                    .useDefaultLocationEngine(false)
                                    .build()
                            )
                            map.locationComponent.setLocationComponentEnabled(true)
                            map.locationComponent.cameraMode = CameraMode.NONE
                            map.locationComponent.renderMode = RenderMode.NORMAL
                        }
                        cameraState.map = map
                        map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraState.position.toMapLibre()))
                        map.addOnCameraMoveListener { cameraState.syncFromMap(map.cameraPosition) }
                        map.addOnCameraIdleListener { cameraState.syncFromMap(map.cameraPosition) }
                        map.addOnCameraMoveStartedListener { reason -> cameraState.moveStartedReason = reason }
                        map.addOnMapClickListener { onMapClick(); false }
                        mapRef = map
                        styleReady = true
                        onMapReady(map)
                        onMapLoaded()
                    }
                }
            }
        },
    )

    // Push dynamic content into the style layers whenever it changes.
    LaunchedEffect(styleReady, rocketLatLng, rocketFresh, accuracyRadiusM, flightPath) {
        val style = mapRef?.style?.takeIf { styleReady && it.isFullyLoaded } ?: return@LaunchedEffect
        updateContentLayers(style, rocketLatLng, rocketFresh, accuracyRadiusM, flightPath)
    }

    // Feed the app's fused-location fixes into the my-location component. Skip the 0,0
    // fallback so the blue dot doesn't flash at null-island before the first real fix.
    LaunchedEffect(styleReady, userLocation?.latitude, userLocation?.longitude) {
        val map = mapRef ?: return@LaunchedEffect
        val loc = userLocation ?: return@LaunchedEffect
        if ((loc.latitude != 0.0 || loc.longitude != 0.0) && map.locationComponent.isLocationComponentActivated) {
            map.locationComponent.forceLocationUpdate(loc)
        }
    }

    // Forward host lifecycle into MapView (else it renders black / leaks).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mv = mapView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                Lifecycle.Event.ON_DESTROY -> mv.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onDestroy()
        }
    }
}

/** Adds empty sources + layers once, ordered so the rocket dot sits on top. */
private fun setupContentLayers(style: Style) {
    style.addSource(GeoJsonSource(SRC_ACCURACY))
    style.addSource(GeoJsonSource(SRC_PATH))
    style.addSource(GeoJsonSource(SRC_ROCKET))

    style.addLayer(
        FillLayer(LYR_ACCURACY_FILL, SRC_ACCURACY).withProperties(
            PropertyFactory.fillColor(COLOR_GREEN),
            PropertyFactory.fillOpacity(0.19f),
        )
    )
    style.addLayer(
        LineLayer(LYR_ACCURACY_LINE, SRC_ACCURACY).withProperties(
            PropertyFactory.lineColor(COLOR_GREEN),
            PropertyFactory.lineOpacity(0.5f),
            PropertyFactory.lineWidth(1f),
        )
    )
    style.addLayer(
        LineLayer(LYR_PATH, SRC_PATH).withProperties(
            PropertyFactory.lineColor(COLOR_PATH),
            PropertyFactory.lineWidth(8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        CircleLayer(LYR_ROCKET, SRC_ROCKET).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(COLOR_GREEN),
            PropertyFactory.circleStrokeColor(COLOR_WHITE),
            PropertyFactory.circleStrokeWidth(2f),
        )
    )
}

/** Updates source geometry + freshness colors each frame the inputs change. */
private fun updateContentLayers(
    style: Style,
    rocketLatLng: LatLng,
    rocketFresh: Boolean,
    accuracyRadiusM: Double,
    flightPath: List<LatLng>,
) {
    val color = if (rocketFresh) COLOR_GREEN else COLOR_RED

    // Accuracy ring: a geographic polygon (meters radius) — MapLibre circle-radius is
    // in pixels, so a meters ring must be an actual polygon.
    (style.getSourceAs<GeoJsonSource>(SRC_ACCURACY))?.setGeoJson(
        accuracyRingPolygon(rocketLatLng, accuracyRadiusM)
    )
    (style.getLayer(LYR_ACCURACY_FILL) as? FillLayer)?.setProperties(PropertyFactory.fillColor(color))
    (style.getLayer(LYR_ACCURACY_LINE) as? LineLayer)?.setProperties(PropertyFactory.lineColor(color))

    // Flight path
    val pathGeo = if (flightPath.size >= 2)
        LineString.fromLngLats(flightPath.map { Point.fromLngLat(it.longitude, it.latitude) })
    else
        LineString.fromLngLats(emptyList())
    (style.getSourceAs<GeoJsonSource>(SRC_PATH))?.setGeoJson(pathGeo)

    // Rocket marker dot
    (style.getSourceAs<GeoJsonSource>(SRC_ROCKET))?.setGeoJson(
        Point.fromLngLat(rocketLatLng.longitude, rocketLatLng.latitude)
    )
    (style.getLayer(LYR_ROCKET) as? CircleLayer)?.setProperties(PropertyFactory.circleColor(color))
}

/** Builds a closed 64-gon approximating a circle of [radiusM] meters around [center]. */
private fun accuracyRingPolygon(center: LatLng, radiusM: Double): Polygon {
    val steps = 64
    val earthR = 6378137.0
    val latRad = center.latitude * PI / 180.0
    val ring = ArrayList<Point>(steps + 1)
    for (i in 0..steps) {
        val theta = 2.0 * PI * i / steps
        val dNorth = radiusM * kotlin.math.sin(theta)
        val dEast = radiusM * cos(theta)
        val dLat = dNorth / earthR * (180.0 / PI)
        val dLng = dEast / (earthR * cos(latRad)) * (180.0 / PI)
        ring.add(Point.fromLngLat(center.longitude + dLng, center.latitude + dLat))
    }
    return Polygon.fromLngLats(listOf(ring))
}

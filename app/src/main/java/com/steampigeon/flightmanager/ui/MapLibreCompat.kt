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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.location.Location
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.steampigeon.flightmanager.R
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.hypot
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

// Deadband for pushing the camera to the native map.
//
// The camera controller's Kalman filters converge ASYMPTOTICALLY, so they never
// stop emitting marginally different values — measured at ~120 native moveCamera
// calls per second on an idle map, i.e. one per display frame, forever, for
// changes far below one pixel. Each one invalidates the GL surface and competes
// with MapLibre's touch handling on the main thread, which is what made the
// first gesture feel unresponsive: only once the controller's gesture backoff
// engaged did the flood stop and the map become responsive.
//
// Thresholds are sub-pixel at the deepest zoom the app reaches (1e-7° ≈ 1.1 cm,
// ~0.6 px at zoom 22). Comparison is against the last APPLIED position, so
// changes below the threshold accumulate rather than being lost.
private const val CAM_EPS_DEG_TARGET  = 1e-7
private const val CAM_EPS_ZOOM        = 0.0005f
private const val CAM_EPS_DEG_TILT    = 0.02f
private const val CAM_EPS_DEG_BEARING = 0.02f

/** True when [other] differs from this by less than a perceptible amount. */
private fun CamPos.isImperceptiblyCloseTo(other: CamPos): Boolean =
    kotlin.math.abs(target.latitude  - other.target.latitude)  < CAM_EPS_DEG_TARGET &&
    kotlin.math.abs(target.longitude - other.target.longitude) < CAM_EPS_DEG_TARGET &&
    kotlin.math.abs(zoom    - other.zoom)    < CAM_EPS_ZOOM &&
    kotlin.math.abs(tilt    - other.tilt)    < CAM_EPS_DEG_TILT &&
    // Bearing wraps: 359.99° and 0.01° are 0.02° apart, not 359.98°.
    kotlin.math.abs(((bearing - other.bearing + 540f) % 360f) - 180f) < CAM_EPS_DEG_BEARING

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
    private var _gesturing by mutableStateOf(false)
    // Last position actually pushed to the native map — the deadband reference.
    // Deliberately NOT Compose state: it must not participate in recomposition.
    private var _lastApplied: CamPos = initial

    /**
     * True from the start of a user gesture until the camera settles.
     *
     * Latched, rather than derived from [moveStartedReason], because every
     * programmatic `moveCamera` fires `onCameraMoveStarted` with
     * REASON_API_ANIMATION (3). That overwrote the user's REASON_API_GESTURE (1)
     * within ~3 ms, so the camera controller could not reliably tell that a
     * gesture was underway and kept driving the camera against it — the first
     * gesture felt like it was fighting the map, while later ones (after the
     * controller had finally latched its 5 s backoff) felt fine.
     */
    val isGesturing: Boolean get() = _gesturing

    var moveStartedReason: Int
        get() = _reason
        internal set(value) {
            // A programmatic move must never clear a live gesture.
            if (value == REASON_GESTURE) _gesturing = true
            _reason = value
        }

    var position: CamPos
        get() = _position
        set(value) {
            // While the user is gesturing the camera is theirs. Dropping the
            // write here (rather than relying on the controller noticing) makes
            // the hand-off immediate and independent of recomposition timing.
            if (_gesturing) return
            _position = value
            // Skip sub-perceptual moves. Compared against the last APPLIED
            // position so tiny changes accumulate until they matter, rather
            // than the camera slowly drifting out of sync.
            if (value.isImperceptiblyCloseTo(_lastApplied)) return
            _lastApplied = value
            map?.moveCamera(CameraUpdateFactory.newCameraPosition(value.toMapLibre()))
        }

    /** Called from the map's own camera listeners — updates state WITHOUT re-moving the map. */
    internal fun syncFromMap(p: CameraPosition) {
        _position = p.toCamPos()
        // The user moved the map, so the deadband reference is wherever they
        // left it — not where we last pushed the camera.
        _lastApplied = _position
    }

    /** Camera has settled: the gesture (and any fling) is over. */
    internal fun onCameraIdle(p: CameraPosition) {
        _gesturing = false
        // Clear the reason too. It is otherwise sticky at REASON_GESTURE for the
        // rest of the session, and any consumer polling it would keep seeing a
        // gesture that finished long ago.
        _reason = -1
        _position = p.toCamPos()
        _lastApplied = _position
    }
}

/** Reads the bundled satellite style JSON (same asset the offline pack renders from). */
fun loadSatelliteStyleJson(context: Context): String =
    context.assets.open("satellite_style.json").bufferedReader().use { it.readText() }

// Layer / source ids
/**
 * One recorded point of the rocket's track: ground position plus altitude above
 * ground level, in metres.
 *
 * The altitude used to be discarded at the map boundary, which is why the path
 * drew flat on the terrain in 3D.
 */
data class PathPoint(val latitude: Double, val longitude: Double, val altitudeM: Float)

private const val SRC_ACCURACY = "accuracy-src"
private const val SRC_PATH = "path-src"
private const val SRC_PATH_CURTAIN = "path-curtain-src"
private const val SRC_ROCKET = "rocket-src"
private const val LYR_ACCURACY_FILL = "accuracy-fill"
private const val LYR_ACCURACY_LINE = "accuracy-line"
private const val LYR_PATH = "path-line"
private const val LYR_PATH_CURTAIN = "path-curtain"
private const val LYR_ROCKET = "rocket-dot"

// Altitude curtain under the flight path.  MapLibre's line layer is strictly
// ground-plane — the style spec has no 3D polyline — so lifting the track into
// the air means extruded polygons: one quad per segment, extruded from the
// ground to that segment's AGL.  fill-extrusion base/height are constant per
// feature, so the profile is a staircase; CURTAIN_SUBDIVISIONS interpolates
// between telemetry fixes to make the steps small enough to read as a curve.
private const val CURTAIN_SUBDIVISIONS = 8

// Half-width of each curtain quad, in metres.  Wide enough that the wall stays
// visible edge-on when the camera looks along the track, narrow enough not to
// misrepresent the track's ground position.
private const val CURTAIN_HALF_WIDTH_M = 0.75

// A segment whose peak is below this contributes no useful height information
// and would just add z-fighting clutter over the ground line.
private const val CURTAIN_MIN_ALT_M = 0.5f

// Rocket marker icons, pre-tinted for the two freshness states.  Two images
// rather than one SDF: an SDF would flatten the drawable to a tintable
// silhouette, and the sprite is only ~72 px square, so carrying both costs
// nothing.
private const val IMG_ROCKET_FRESH = "rocket-icon-fresh"
private const val IMG_ROCKET_STALE = "rocket-icon-stale"
private const val ROCKET_ICON_PX = 72

// Keep GeoJSON geometry exact well past the default source maxzoom of 18.
private const val MAX_GEOJSON_ZOOM = 22

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
    flightPath: List<PathPoint>,
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
                        // Register the sprites first — the symbol layer created by
                        // setupContentLayers references them by name.
                        addRocketIcons(mv.context, style)
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
                        // Mirror only USER-driven movement. Mirroring our own
                        // programmatic moves closed a feedback loop — sync →
                        // recomposition → camera controller writes → moveCamera
                        // → sync → … — measured at ~167 camera writes/second
                        // during a single swipe. The controller already sets
                        // _position before moving the map, so there is nothing
                        // to learn from echoing its own move back.
                        map.addOnCameraMoveListener {
                            if (cameraState.isGesturing) cameraState.syncFromMap(map.cameraPosition)
                        }
                        map.addOnCameraIdleListener { cameraState.onCameraIdle(map.cameraPosition) }
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
    // A GeoJSON source is tiled, and its DEFAULTS quietly wreck small geometry
    // at high zoom: maxzoom 18 means past z18 MapLibre rescales z18 tile
    // geometry instead of re-tiling, and tolerance 0.375 runs Douglas-Peucker
    // simplification in tile units, dropping vertices. Together they turn the
    // accuracy ring into a visible polygon — worse the further you zoom in.
    // Raising maxzoom and disabling simplification keeps it a clean circle.
    val preciseGeometry = GeoJsonOptions()
        .withMaxZoom(MAX_GEOJSON_ZOOM)
        .withTolerance(0f)

    style.addSource(GeoJsonSource(SRC_ACCURACY, preciseGeometry))
    style.addSource(GeoJsonSource(SRC_PATH, preciseGeometry))
    style.addSource(GeoJsonSource(SRC_PATH_CURTAIN, preciseGeometry))
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
    // Altitude curtain, above the ground track so the wall reads as rising from
    // it.  Height comes from each feature's own "height" property rather than a
    // layer constant, which is what lets one layer draw the whole profile.
    style.addLayer(
        FillExtrusionLayer(LYR_PATH_CURTAIN, SRC_PATH_CURTAIN).withProperties(
            PropertyFactory.fillExtrusionColor(COLOR_PATH),
            PropertyFactory.fillExtrusionHeight(Expression.get("height")),
            PropertyFactory.fillExtrusionBase(0f),
            // Mostly opaque. The curtain is a chain of separate prisms, so each
            // segment carries end-cap faces where it meets its neighbour; at low
            // opacity those internal faces all show through and the wall reads
            // as a ladder. Higher opacity hides them behind the front face while
            // still letting terrain show through enough to keep bearings.
            PropertyFactory.fillExtrusionOpacity(0.75f),
        )
    )
    style.addLayer(
        SymbolLayer(LYR_ROCKET, SRC_ROCKET).withProperties(
            PropertyFactory.iconImage(IMG_ROCKET_FRESH),
            // The marker must always be drawn: MapLibre's default collision
            // detection would hide it behind basemap labels, and a locator you
            // are trying to walk to is the one thing that can't disappear.
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            // Keep it upright and screen-sized when the map is rotated/tilted.
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
        )
    )
}

/**
 * Rasterises the rocket vector drawable twice — tinted for the fresh and stale
 * states — and registers both with the style.  Must run before the symbol layer
 * references them, and again on every style reload (a style change drops its
 * image sprite).
 */
private fun addRocketIcons(context: Context, style: Style) {
    fun sprite(bodyColor: Int): Bitmap {
        val bitmap = createBitmap(ROCKET_ICON_PX, ROCKET_ICON_PX)
        val canvas = Canvas(bitmap)

        fun draw(color: Int, inset: Int) {
            // A fresh drawable per pass: mutate() detaches the constant state,
            // but reusing one instance would still carry the previous filter.
            AppCompatResources.getDrawable(context, R.drawable.rocket)!!.mutate().apply {
                setBounds(inset, inset, ROCKET_ICON_PX - inset, ROCKET_ICON_PX - inset)
                colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                draw(canvas)
            }
        }

        // White silhouette behind a slightly inset tinted body, giving the
        // marker an outline. The dot it replaces had a white stroke for the
        // same reason — a green marker over green tree canopy is invisible
        // without it, and this map is used to walk to a landed rocket.
        draw(COLOR_WHITE, 0)
        draw(bodyColor, (ROCKET_ICON_PX * 0.08f).toInt())
        return bitmap
    }
    // Tinted rather than full-colour so the fresh/stale distinction the dot
    // carried survives — a stale fix means the position can't be trusted.
    style.addImage(IMG_ROCKET_FRESH, sprite(COLOR_GREEN))
    style.addImage(IMG_ROCKET_STALE, sprite(COLOR_RED))
}

/** Updates source geometry + freshness colors each frame the inputs change. */
private fun updateContentLayers(
    style: Style,
    rocketLatLng: LatLng,
    rocketFresh: Boolean,
    accuracyRadiusM: Double,
    flightPath: List<PathPoint>,
) {
    val color = if (rocketFresh) COLOR_GREEN else COLOR_RED

    // Accuracy ring: a geographic polygon (meters radius) — MapLibre circle-radius is
    // in pixels, so a meters ring must be an actual polygon.
    (style.getSourceAs<GeoJsonSource>(SRC_ACCURACY))?.setGeoJson(
        accuracyRingPolygon(rocketLatLng, accuracyRadiusM)
    )
    (style.getLayer(LYR_ACCURACY_FILL) as? FillLayer)?.setProperties(PropertyFactory.fillColor(color))
    (style.getLayer(LYR_ACCURACY_LINE) as? LineLayer)?.setProperties(PropertyFactory.lineColor(color))

    // Flight path: the ground track, plus the altitude curtain standing on it.
    val pathGeo = if (flightPath.size >= 2)
        LineString.fromLngLats(flightPath.map { Point.fromLngLat(it.longitude, it.latitude) })
    else
        LineString.fromLngLats(emptyList())
    (style.getSourceAs<GeoJsonSource>(SRC_PATH))?.setGeoJson(pathGeo)
    (style.getSourceAs<GeoJsonSource>(SRC_PATH_CURTAIN))?.setGeoJson(altitudeCurtain(flightPath))

    // Rocket marker
    (style.getSourceAs<GeoJsonSource>(SRC_ROCKET))?.setGeoJson(
        Point.fromLngLat(rocketLatLng.longitude, rocketLatLng.latitude)
    )
    (style.getLayer(LYR_ROCKET) as? SymbolLayer)?.setProperties(
        PropertyFactory.iconImage(if (rocketFresh) IMG_ROCKET_FRESH else IMG_ROCKET_STALE)
    )
}

/**
 * Builds the altitude curtain: a wall of extruded quads hanging from the flight
 * path down to the ground, whose top edge traces the altitude profile.
 *
 * Each output feature is a thin rectangle in plan view carrying a `height`
 * property (metres AGL) for `fill-extrusion-height`.  Because that height is
 * constant across a feature, consecutive segments form steps; each telemetry
 * interval is therefore split into [CURTAIN_SUBDIVISIONS] pieces with linearly
 * interpolated altitude so the steps read as a smooth arc.
 *
 * Returns an empty collection for a path with no meaningful altitude, so a
 * pad-bound or ground-level track doesn't draw a degenerate wall.
 */
internal fun altitudeCurtain(path: List<PathPoint>): FeatureCollection {
    if (path.size < 2) return FeatureCollection.fromFeatures(emptyList())

    val features = ArrayList<Feature>((path.size - 1) * CURTAIN_SUBDIVISIONS)
    val metersPerDegLat = 111_320.0

    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]

        for (s in 0 until CURTAIN_SUBDIVISIONS) {
            val t0 = s.toDouble() / CURTAIN_SUBDIVISIONS
            val t1 = (s + 1).toDouble() / CURTAIN_SUBDIVISIONS

            val lat0 = a.latitude + (b.latitude - a.latitude) * t0
            val lon0 = a.longitude + (b.longitude - a.longitude) * t0
            val lat1 = a.latitude + (b.latitude - a.latitude) * t1
            val lon1 = a.longitude + (b.longitude - a.longitude) * t1

            // Height of this sub-segment: the mean of its endpoints, so the
            // staircase straddles the true profile instead of lagging it.
            val alt0 = a.altitudeM + (b.altitudeM - a.altitudeM) * t0.toFloat()
            val alt1 = a.altitudeM + (b.altitudeM - a.altitudeM) * t1.toFloat()
            val height = (alt0 + alt1) / 2f
            if (height < CURTAIN_MIN_ALT_M) continue

            // Offset perpendicular to the sub-segment to give the wall width.
            // Longitude degrees shrink by cos(latitude), so convert to metres
            // before taking the normal or the wall skews with latitude.
            val cosLat = cos(lat0 * PI / 180.0).coerceAtLeast(1e-6)
            val dxM = (lon1 - lon0) * metersPerDegLat * cosLat
            val dyM = (lat1 - lat0) * metersPerDegLat
            val lenM = hypot(dxM, dyM)
            // Zero-length sub-segment (rocket stationary): nothing to extrude.
            if (lenM < 1e-6) continue

            val nxM = -dyM / lenM * CURTAIN_HALF_WIDTH_M
            val nyM = dxM / lenM * CURTAIN_HALF_WIDTH_M
            val dLon = nxM / (metersPerDegLat * cosLat)
            val dLat = nyM / metersPerDegLat

            val ring = listOf(
                Point.fromLngLat(lon0 + dLon, lat0 + dLat),
                Point.fromLngLat(lon1 + dLon, lat1 + dLat),
                Point.fromLngLat(lon1 - dLon, lat1 - dLat),
                Point.fromLngLat(lon0 - dLon, lat0 - dLat),
                Point.fromLngLat(lon0 + dLon, lat0 + dLat),
            )
            features.add(
                Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                    addNumberProperty("height", height)
                }
            )
        }
    }
    return FeatureCollection.fromFeatures(features)
}

/**
 * Builds a closed polygon approximating a circle of [radiusM] meters around
 * [center].
 *
 * Longitude degrees shrink by cos(latitude), so the east offset is divided by
 * it — that makes a true *ground* circle, which Mercator (being conformal)
 * then renders as a screen circle.
 *
 * Segment count is generous because the ring can fill the screen when zoomed
 * in on a tight fix: at 256 segments the worst-case deviation from a true arc
 * is r × (1 − cos(π/256)) ≈ r/13600, i.e. sub-pixel even at a 2000 px radius.
 */
private fun accuracyRingPolygon(center: LatLng, radiusM: Double): Polygon {
    val steps = 256
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

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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

// The gesture reason constant, surfaced so MapCameraController can compare against it
// exactly as it compared against Google's CameraMoveStartedReason.GESTURE.
const val REASON_GESTURE = MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE

// MapLibre's hard tilt ceiling (MapLibreConstants.MAXIMUM_PITCH). Camera tilt above this is
// silently clamped, and setMaxPitchPreference refuses any value outside 0..60 — it logs
// "value is in unsupported range" and leaves the limit untouched. Surfaced here so
// MapCameraController can keep its tilt range inside what the SDK will actually honour.
const val MAPLIBRE_MAX_PITCH = 60f

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
 * One recorded point of the rocket's track: ground position, altitude above
 * ground level in metres, and the wall-clock time the fix was received.
 *
 * The altitude used to be discarded at the map boundary, which is why the path
 * drew flat on the terrain in 3D.
 *
 * [timestampMs] is what lets the second markers land on real one-second
 * boundaries.  Point index is not a substitute: these are live radio fixes, so
 * a dropped packet would slide every later mark forward by an interval.
 *
 * [timeSynthetic] marks a point restored from a recording made before capture
 * times existed.  Its position and altitude are real and draw normally; only its
 * timestamp is a placeholder, so [secondMarkers] steps over it rather than
 * standing a post at a second the rocket was never at.  Keeping the geometry and
 * dropping only the marks is the right trade — the track is where the rocket
 * went, which is recovery data; the marks are a reading aid.
 */
data class PathPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float,
    val timestampMs: Long,
    val timeSynthetic: Boolean = false,
)

private const val SRC_ACCURACY = "accuracy-src"
private const val SRC_PATH = "path-src"
private const val SRC_PATH_CURTAIN = "path-curtain-src"
private const val SRC_PATH_TICKS = "path-ticks-src"
private const val SRC_ROCKET = "rocket-src"
private const val LYR_ACCURACY_FILL = "accuracy-fill"
private const val LYR_ACCURACY_LINE = "accuracy-line"
private const val LYR_PATH = "path-line"
private const val LYR_PATH_CURTAIN = "path-curtain"
private const val LYR_PATH_TICKS = "path-ticks"
private const val LYR_ROCKET = "rocket-dot"

// Altitude curtain under the flight path.  MapLibre's line layer is strictly
// ground-plane — the style spec has no 3D polyline — so lifting the track into
// the air means extruded polygons: one quad per segment, extruded from the
// ground to that segment's AGL.
//
// fill-extrusion height is constant per feature (a data-driven expression varies
// it BETWEEN features, never across one), and the SDK offers no sloped-top
// option — fill-extrusion-vertical-gradient is shading, not geometry.  A truly
// angled top would need a CustomLayer, i.e. native GL.  So the wall's top edge
// is necessarily a staircase, and smoothness comes from two independent levers.
//
// Lever 1 — riser height.  Each interval is split into sub-quads, budgeted by
// ALTITUDE change rather than a fixed count.  A fixed count divides the *ground*
// run evenly, which is the wrong axis: on a near-vertical boost the track barely
// advances while altitude climbs hundreds of metres, so risers stay tall no
// matter how finely the run is chopped.
//
// Lever 2 — the shape between points, see [PathSpline].  Shrinking risers alone
// leaves a chain of straight chords meeting at a visible corner on every
// telemetry point, which on the ~1 Hz live path is the dominant artefact: a
// second of boost is ~100 m of climb drawn as one straight line.
private const val CURTAIN_TARGET_RISER_M = 0.25f

// Backstop on total sub-quads, relaxing the riser only if a path would otherwise
// emit an unreasonable number of features.
//
// Deliberately set high enough NOT to bind on a normal flight, because the term
// that drives it — summed |altitude change| — cannot tell signal from noise, and
// that bit hard.  Raw baro at the 20 Hz archive cadence jitters a few metres
// sample to sample, and summing ABSOLUTE differences over ~2000 samples turns
// that jitter into thousands of metres: a 122 m flight measured 244 m of
// variation clean, but 4000 m with ±3 m of noise.  With the old ceiling of 2000
// quads that inflated the riser from the intended 0.25 m to ~2 m, and on a
// longer record to 4-6 m — so the budget was silently undoing the very smoothing
// it was introduced alongside.  Measured on a real archived record; see the
// SESSION_HANDOFF entry for the numbers.
//
// The cost of a high ceiling is feature count on a noisy archived record
// (~16k quads at 0.25 m).  That is affordable because an archived path is built
// once, not per telemetry message.  Genuinely fixing the roughness needs the
// altitude signal de-noised before it is drawn, which is a fidelity decision
// (it lowers the drawn apogee) and is deliberately NOT done here.
private const val CURTAIN_MAX_QUADS = 20_000

// Per-interval safety valve, so one wild altitude jump (a garbled fix, or the
// first sample after a radio dropout) can't consume the whole budget itself.
private const val CURTAIN_MAX_SUBDIVISIONS = 512

// Half-width of each curtain quad, in metres.  Wide enough that the wall stays
// visible edge-on when the camera looks along the track, narrow enough not to
// misrepresent the track's ground position.
private const val CURTAIN_HALF_WIDTH_M = 0.75

// A segment whose peak is below this contributes no useful height information
// and would just add z-fighting clutter over the ground line.
private const val CURTAIN_MIN_ALT_M = 0.5f

// One-second markers: full-height posts standing on the ground track up to the
// path's altitude at each whole second of recording, turning the curtain into a
// ruler you can read climb rate off directly.
private const val TICK_INTERVAL_MS = 1_000L

// Wider than the curtain (and given a little length along the track) so the post
// protrudes from the wall on both faces.  Coincident geometry would z-fight
// instead of reading as a mark.
private const val TICK_HALF_WIDTH_M = 1.6
private const val TICK_HALF_LENGTH_M = 0.4

// Ceiling on marker count.  A recording left running for an hour would otherwise
// emit 3600 posts, all rebuilt on every telemetry message.
private const val TICK_MAX_COUNT = 600

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
// Cyan against the path's orange: near-complementary, so the second markers
// stay legible where they cross the curtain and over satellite imagery alike.
private const val COLOR_PATH_TICK = 0xFF00E5FF.toInt()

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
                    // No setMaxPitchPreference call: 60° is already the default ceiling and
                    // also the highest value the setter accepts, so raising it isn't possible
                    // (see MAPLIBRE_MAX_PITCH). The previous 85.0 here was rejected outright.
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
    style.addSource(GeoJsonSource(SRC_PATH_TICKS, preciseGeometry))
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
            //
            // Full opacity was tried and reverted: it removed the see-through
            // without making the top edge any smoother, which is what ruled the
            // end-cap faces out as the cause of the roughness. The cause is the
            // riser height — see CURTAIN_MAX_QUADS.
            PropertyFactory.fillExtrusionOpacity(0.75f),
        )
    )
    // One-second markers, added after the curtain so they sort in front of it
    // where the two overlap.  Fully opaque: these are the reference marks the
    // curtain is read against, so they must not pick up the wall's colour.
    style.addLayer(
        FillExtrusionLayer(LYR_PATH_TICKS, SRC_PATH_TICKS).withProperties(
            PropertyFactory.fillExtrusionColor(COLOR_PATH_TICK),
            PropertyFactory.fillExtrusionHeight(Expression.get("height")),
            PropertyFactory.fillExtrusionBase(0f),
            PropertyFactory.fillExtrusionOpacity(1.0f),
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
    (style.getSourceAs<GeoJsonSource>(SRC_PATH_TICKS))?.setGeoJson(secondMarkers(flightPath))

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
 * constant across a feature, consecutive features form steps; each telemetry
 * interval is therefore split into linearly interpolated sub-segments, as many
 * as it takes to keep every riser under [CURTAIN_MAX_RISER_M], so the steps read
 * as a smooth arc.
 *
 * Returns an empty collection for a path with no meaningful altitude, so a
 * pad-bound or ground-level track doesn't draw a degenerate wall.
 */
internal fun altitudeCurtain(path: List<PathPoint>): FeatureCollection {
    if (path.size < 2) return FeatureCollection.fromFeatures(emptyList())

    val features = ArrayList<Feature>(path.size - 1)
    val metersPerDegLat = 111_320.0
    val spline = PathSpline(path)
    val riser = curtainRiser(path)

    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]
        val subdivisions = curtainSubdivisions(a.altitudeM, b.altitudeM, riser)

        for (s in 0 until subdivisions) {
            val t0 = s.toDouble() / subdivisions
            val t1 = (s + 1).toDouble() / subdivisions

            val lat0 = spline.latitudeAt(i, t0)
            val lon0 = spline.longitudeAt(i, t0)
            val lat1 = spline.latitudeAt(i, t1)
            val lon1 = spline.longitudeAt(i, t1)

            // Height of this sub-segment: the mean of its endpoints, so the
            // staircase straddles the true profile instead of lagging it.
            val alt0 = spline.altitudeAt(i, t0)
            val alt1 = spline.altitudeAt(i, t1)
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
 * How many pieces one telemetry interval must be split into to keep each
 * fill-extrusion riser under [riserM].
 *
 * Level flight needs exactly one quad — subdividing it would emit identical
 * heights and buy nothing — so the floor is 1, not a fixed count.
 */
internal fun curtainSubdivisions(altFromM: Float, altToM: Float, riserM: Float): Int =
    ceil(abs(altToM - altFromM) / riserM)
        .toInt()
        .coerceIn(1, CURTAIN_MAX_SUBDIVISIONS)

/**
 * Riser height to aim for across [path]: [CURTAIN_TARGET_RISER_M], relaxed only
 * if [CURTAIN_MAX_QUADS] would otherwise be exceeded.
 *
 * Total quads land near (summed altitude change / riser), which is why that sum
 * is the backstop's input — but see [CURTAIN_MAX_QUADS] for why the ceiling is
 * set high enough that it does not normally bind.  The sum counts sensor noise
 * as though it were flight profile, so letting it drive the riser in ordinary
 * use made the wall coarser precisely on the high-rate data that most needed it
 * fine.
 */
internal fun curtainRiser(path: List<PathPoint>): Float {
    if (path.size < 2) return CURTAIN_TARGET_RISER_M
    var variation = 0f
    for (i in 0 until path.size - 1) variation += abs(path[i + 1].altitudeM - path[i].altitudeM)
    return maxOf(CURTAIN_TARGET_RISER_M, variation / CURTAIN_MAX_QUADS)
}

/**
 * Smooth interpolation through the recorded points, replacing the straight chords
 * that used to join them.
 *
 * Cubic Hermite throughout, but with **different tangent rules for altitude and
 * for position**, because they have different failure modes.
 *
 * *Altitude* uses Fritsch–Carlson monotone tangents.  A plain Catmull-Rom spline
 * overshoots near a sharp extremum, and the sharpest feature in a flight profile
 * is apogee — so it would draw the rocket higher than it ever flew, and a reader
 * measuring apogee off the curtain would get a number the rocket never reached.
 * Smoothing may not invent altitude.  Monotone tangents guarantee the curve stays
 * within the recorded values on every interval: no overshoot, and any flat run
 * stays exactly flat.
 *
 * *Position* uses ordinary Catmull-Rom tangents.  A ground track has no
 * meaningful extremum to preserve, and its overshoot is sub-metre on a curve of
 * hundreds — well under the GPS accuracy the points themselves carry — while
 * monotone limiting there would flatten genuine curvature in a turn.
 *
 * Parameterised by point index, matching how the curtain walks intervals, so a
 * caller with a *time* fraction inside interval `i` can pass it straight in.
 */
internal class PathSpline(private val path: List<PathPoint>) {
    private val n = path.size
    private val mLat = DoubleArray(n)
    private val mLon = DoubleArray(n)
    private val mAlt = FloatArray(n)

    init {
        if (n >= 2) {
            catmullRomTangents({ path[it].latitude }, mLat)
            catmullRomTangents({ path[it].longitude }, mLon)
            monotoneTangents()
        }
    }

    /** Centred differences, one-sided at the ends. */
    private inline fun catmullRomTangents(value: (Int) -> Double, out: DoubleArray) {
        for (i in 0 until n) {
            val prev = value(if (i == 0) 0 else i - 1)
            val next = value(if (i == n - 1) n - 1 else i + 1)
            // Halved for interior points (span of 2 intervals), full at the ends
            // where the span is 1.
            out[i] = if (i == 0 || i == n - 1) next - prev else (next - prev) / 2.0
        }
    }

    /**
     * Fritsch–Carlson: start from centred differences, then clamp so no interval
     * can overshoot the values bracketing it.
     */
    private fun monotoneTangents() {
        val d = FloatArray(n - 1)                       // secant slopes
        for (i in 0 until n - 1) d[i] = path[i + 1].altitudeM - path[i].altitudeM

        for (i in 0 until n) {
            mAlt[i] = when (i) {
                0 -> d[0]
                n - 1 -> d[n - 2]
                else -> (d[i - 1] + d[i]) / 2f
            }
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                // A flat interval must stay exactly flat — a rocket sitting on the
                // ground may not bulge upward between two identical readings.
                mAlt[i] = 0f
                mAlt[i + 1] = 0f
            } else {
                val a = mAlt[i] / d[i]
                val b = mAlt[i + 1] / d[i]
                // Negative ratio means the tangent points against the interval, so
                // the curve would leave the bracket immediately.
                if (a < 0f) mAlt[i] = 0f
                if (b < 0f) mAlt[i + 1] = 0f
                val s = a * a + b * b
                if (s > 9f) {
                    val tau = 3f / kotlin.math.sqrt(s)
                    mAlt[i] = tau * a * d[i]
                    mAlt[i + 1] = tau * b * d[i]
                }
            }
        }
    }

    /** Altitude at fraction [t] through interval [i]. */
    fun altitudeAt(i: Int, t: Double): Float {
        if (n < 2) return path.firstOrNull()?.altitudeM ?: 0f
        val tf = t.toFloat()
        val t2 = tf * tf
        val t3 = t2 * tf
        return (2 * t3 - 3 * t2 + 1) * path[i].altitudeM +
            (t3 - 2 * t2 + tf) * mAlt[i] +
            (-2 * t3 + 3 * t2) * path[i + 1].altitudeM +
            (t3 - t2) * mAlt[i + 1]
    }

    fun latitudeAt(i: Int, t: Double): Double = hermite(
        path[i].latitude, mLat[i], path[i + 1].latitude, mLat[i + 1], t
    )

    fun longitudeAt(i: Int, t: Double): Double = hermite(
        path[i].longitude, mLon[i], path[i + 1].longitude, mLon[i + 1], t
    )

    private fun hermite(p0: Double, m0: Double, p1: Double, m1: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * p0 +
            (t3 - 2 * t2 + t) * m0 +
            (-2 * t3 + 3 * t2) * p1 +
            (t3 - t2) * m1
    }
}

/**
 * Builds the one-second markers: contrasting posts standing on the ground track,
 * each extruded from the ground to the path's interpolated altitude at a whole
 * second of recording time.
 *
 * Marks are placed at real elapsed time from the first recorded fix, found by
 * interpolating between the two samples that bracket each boundary — so they
 * stay on true seconds through a dropped packet or a variable radio interval,
 * which counting samples would not.
 *
 * The mark at t=0 is skipped: the rocket is on the pad, so its post would have
 * no height to draw.
 *
 * Points carrying a synthetic timestamp ([PathPoint.timeSynthetic] — a path
 * restored from a recording made before capture times existed) are stepped over.
 * Elapsed time is measured from the first *real* fix, so a path that mixes a
 * restored prefix with newly received points still marks the live portion
 * correctly instead of anchoring to a placeholder zero.
 */
internal fun secondMarkers(path: List<PathPoint>): FeatureCollection {
    if (path.size < 2) return FeatureCollection.fromFeatures(emptyList())

    val firstReal = path.indexOfFirst { !it.timeSynthetic }
    val lastReal = path.indexOfLast { !it.timeSynthetic }
    // Nothing carries a real capture time — the whole path predates timestamps.
    if (firstReal < 0 || lastReal <= firstReal) return FeatureCollection.fromFeatures(emptyList())

    val startMs = path[firstReal].timestampMs
    val endMs = path[lastReal].timestampMs
    // Non-monotonic timestamps (a clock adjustment mid-recording) leave no
    // meaningful time axis to mark up.
    if (endMs <= startMs) return FeatureCollection.fromFeatures(emptyList())

    val features = ArrayList<Feature>()
    val metersPerDegLat = 111_320.0
    // The same curve the curtain is built from.  Interpolating a mark linearly
    // while the wall beside it curves would stand the post's top off the wall's
    // top edge — by metres, where the two disagree most.
    val spline = PathSpline(path)
    var index = firstReal    // walks forward with the marks; both are ordered

    var elapsed = TICK_INTERVAL_MS
    while (startMs + elapsed <= endMs && features.size < TICK_MAX_COUNT) {
        val markMs = startMs + elapsed
        elapsed += TICK_INTERVAL_MS

        while (index < path.size - 2 && path[index + 1].timestampMs < markMs) index++
        val a = path[index]
        val b = path[index + 1]
        // Either endpoint's time is a placeholder, so where this second falls
        // between them is unknowable.
        if (a.timeSynthetic || b.timeSynthetic) continue

        val span = (b.timestampMs - a.timestampMs).toDouble()
        val f = if (span > 0.0) ((markMs - a.timestampMs) / span).coerceIn(0.0, 1.0) else 0.0

        val alt = spline.altitudeAt(index, f)
        if (alt < CURTAIN_MIN_ALT_M) continue

        val lat = spline.latitudeAt(index, f)
        val lon = spline.longitudeAt(index, f)

        // Orient the post across the direction of travel, matching the curtain.
        val cosLat = cos(lat * PI / 180.0).coerceAtLeast(1e-6)
        val dxM = (b.longitude - a.longitude) * metersPerDegLat * cosLat
        val dyM = (b.latitude - a.latitude) * metersPerDegLat
        val lenM = hypot(dxM, dyM)
        // A rocket descending under canopy in still air can hold one lat/lon
        // across the whole interval.  It still has altitude worth marking, so
        // fall back to a north-aligned post rather than dropping the mark.
        val ux = if (lenM < 1e-6) 0.0 else dxM / lenM
        val uy = if (lenM < 1e-6) 1.0 else dyM / lenM

        // Half-diagonals of the rectangle: along-track length, across-track width.
        val alongX = ux * TICK_HALF_LENGTH_M
        val alongY = uy * TICK_HALF_LENGTH_M
        val acrossX = -uy * TICK_HALF_WIDTH_M
        val acrossY = ux * TICK_HALF_WIDTH_M

        fun corner(sx: Int, sy: Int): Point {
            val mx = alongX * sx + acrossX * sy
            val my = alongY * sx + acrossY * sy
            return Point.fromLngLat(
                lon + mx / (metersPerDegLat * cosLat),
                lat + my / metersPerDegLat,
            )
        }

        val ring = listOf(
            corner(-1, +1), corner(+1, +1), corner(+1, -1), corner(-1, -1), corner(-1, +1),
        )
        features.add(
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                addNumberProperty("height", alt)
            }
        )
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

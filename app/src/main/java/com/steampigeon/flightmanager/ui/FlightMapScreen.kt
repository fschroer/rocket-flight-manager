package com.steampigeon.flightmanager.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.Configuration
import android.hardware.SensorManager
import android.location.Location
import android.os.Vibrator
import android.os.VibrationEffect
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import com.steampigeon.flightmanager.R
import com.steampigeon.flightmanager.NavDestination
import com.steampigeon.flightmanager.data.BluetoothConnectionState
import com.steampigeon.flightmanager.data.BluetoothManagerRepository
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.LinkQuality
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig
import com.steampigeon.flightmanager.data.LocatorMessageState
import com.steampigeon.flightmanager.data.PadAlertState
import com.steampigeon.flightmanager.data.Quaternionf
import com.steampigeon.flightmanager.data.RocketState
import com.steampigeon.flightmanager.data.SensorHealth
import com.steampigeon.flightmanager.ui.RocketViewModel.Companion.G_FORCE_MS2
import com.steampigeon.flightmanager.ui.RocketViewModel.Companion.RAD2DEG
import com.steampigeon.flightmanager.ui.theme.TelemetryTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.sqrt

private const val messageTimeout = 2000

// Tint for the archived-path control when it is engaged.  Cyan matches the
// curtain's one-second markers, which are the reason to look at an archived
// track in the first place: its timestamps are real flight time.
private const val COLOR_ARCHIVED_ACTIVE = 0xFF00E5FF
private const val actionPanelCollapseDelay = 5000L   // auto-collapse the Rescan/Arm action panel after this idle time

// Consistent semi-transparent overlay background used for all map UI panels and buttons.
// Derived from secondaryContainerLight (#5D6F96) at 75% opacity.
private val mapOverlayBg = Color(0xC05D6F96)
// ── Live-map auto-zoom limit (App Settings) ──────────────────────────────────
// The closest zoom AUTO-zoom will frame to. Pinch is not bound by it: the user
// can always go closer by hand to look at something, and auto-zoom reasserts its
// own framing when the gesture window expires, exactly as it does for any manual
// zoom.
//
// This exists because of GPS error, not because of tile availability. Auto-zoom
// frames a box containing the phone and the rocket, and at close range the
// distance between those two reported positions is mostly the two receivers'
// combined error rather than real separation. The box therefore changes size
// dramatically from one fix to the next, and the fitted zoom chases it: the map
// jumps zoom levels every second or so, exactly when the user is walking the
// last few meters with the phone in their hand.
//
// Capping the fitted zoom stops it. Once the fit asks for something deeper than
// the cap, the filter holds there and the jitter has nowhere to go — the map
// keeps a steady frame instead of pumping.
//
// The cost is that auto-zoom stops short of the closest imagery, which is why it
// is a setting rather than a constant: how much error there is depends on the
// receivers, the sky view and the site.
//
// Range and default are fixed rather than derived from the provider. The
// provider's maxOfflineZoom is a DOWNLOAD cap — chosen for storage, since each
// level costs 4x the tiles — not a statement about what imagery exists: the
// Mapbox source carries tiles to z22. Deriving the camera cap from it would pin
// this control to z19/z20 and leave the deeper levels unreachable on a live
// link, which is the opposite of letting the user find the setting that suits
// their receivers and site.
internal const val MAP_ZOOM_LIMIT_MIN = 18
internal const val MAP_ZOOM_LIMIT_MAX = 22
internal const val MAP_ZOOM_LIMIT_DEFAULT = 20

/**
 * The closest zoom the live map may use — [stored] if the user has chosen one,
 * else [MAP_ZOOM_LIMIT_DEFAULT], clamped to the offered range either way.
 */
internal fun resolveMapMaxZoom(stored: Int?): Int =
    (stored ?: MAP_ZOOM_LIMIT_DEFAULT).coerceIn(MAP_ZOOM_LIMIT_MIN, MAP_ZOOM_LIMIT_MAX)

// ── Auto-center deadband ─────────────────────────────────────────────────────
// The zoom cap above stops auto-zoom pumping on GPS error; this stops auto-CENTER
// doing the same thing to the map's position. Both receivers keep reporting new
// fixes while sitting still, so the framed center wanders by a few meters a
// second forever, and the camera — filtered, but never deadbanded — followed
// every bit of it. The imagery crept under a stationary rocket.
//
// So the camera holds an ANCHOR and only re-latches it when the live center has
// drifted further than the two receivers could plausibly have invented. Inside
// that distance the map does not move at all.
//
// Floor and ceiling on the computed distance. The floor keeps an
// optimistically-reported fix from producing a deadband so small it never trips;
// the ceiling keeps a bad one (a locator under canopy claiming hundreds of
// meters) from pinning the camera somewhere stale while the user walks.
internal const val RECENTER_DEADBAND_MIN_M = 5f
internal const val RECENTER_DEADBAND_MAX_M = 40f

/**
 * How far the auto-center target may drift before the camera follows it, in meters.
 *
 * Built in three steps, because the obvious √(σ_locator² + σ_phone²) is wrong in
 * two compensating-looking ways that do not actually cancel:
 *
 *  1. **The target is a midpoint, not a fix.** With both receivers framed the
 *     camera targets the point between them, and a midpoint moves only half as
 *     far as the two independent errors it is drawn from:
 *     σ_target = ½·√(σ_locator² + σ_phone²). Passing [trackerAccuracyM] as null
 *     says the phone is NOT part of the framing (no fix yet), in which case the
 *     target is the rocket itself and carries the locator's full error.
 *  2. **Both ends of the comparison are noisy.** The deadband is measured from a
 *     latched anchor, and that anchor is itself one noisy sample — so what has to
 *     clear it is the difference of two independent draws, which is √2 times as
 *     jumpy as either: σ_drift = √2·σ_target. Sizing against σ_target instead
 *     costs a factor of √2 of real deadband and trips several times more often
 *     than the arithmetic suggests.
 *  3. **2σ, not 1σ.** A deadband at 1σ is crossed by a large fraction of fixes,
 *     which would leave the camera nudging nearly as often as no deadband at all.
 *
 * Which multiplies out to 2·√2·σ_target — about 8 m for a good fix at both ends
 * (3 m locator, 5 m phone), around 35 m for a poor one.
 *
 * @param locatorHaccM the locator's reported horizontal accuracy — the same
 *   figure drawn as the accuracy ring around the rocket marker.
 * @param trackerAccuracyM the phone's reported accuracy, or null when the phone
 *   has no fix to contribute. Non-positive values mean "not reported" and are
 *   treated as an unknown-but-perfect receiver; the floor covers the shortfall.
 */
internal fun recenterDeadbandM(locatorHaccM: Float, trackerAccuracyM: Float?): Float {
    fun sane(v: Float?) = if (v != null && v.isFinite() && v > 0f) v else 0f
    val locator = sane(locatorHaccM)
    val sigmaTarget = if (trackerAccuracyM != null) {
        val phone = sane(trackerAccuracyM)
        0.5f * sqrt(locator * locator + phone * phone)
    } else locator
    return (2f * sqrt(2f) * sigmaTarget)
        .coerceIn(RECENTER_DEADBAND_MIN_M, RECENTER_DEADBAND_MAX_M)
}

/**
 * [recenterDeadbandM] limited to what the screen can actually absorb.
 *
 * The statistical band knows nothing about zoom, and at recovery range that is
 * not a detail: with the camera framed a few meters across, a band computed from
 * a phone reporting 7 m of accuracy comes out wider than the whole viewport, and
 * the anchor can sit far enough off that a marker leaves the screen. That was
 * reported from the field as "the locator or the phone would be off screen", and
 * it is the failure this exists to prevent.
 *
 * The cap is [BOUNDS_FIT_MARGIN_FRACTION] of the visible width — not an
 * arbitrary fraction, but exactly the margin the bounds fit reserves outside the
 * two markers. Spending that margin on center drift is precisely the slack that
 * is there to be spent, and no more.
 *
 * The cap outranks [RECENTER_DEADBAND_MIN_M]. That floor exists so an
 * optimistic fix cannot produce a band too small to ever trip; a screen showing
 * less ground than the floor is a different situation, and there the floor is
 * the thing that is wrong.
 *
 * @param metersPerDevicePx from [metersPerDevicePx] — device pixels, not logical
 *   ones. Using logical pixels here is what made the original viewport check off
 *   by the display density and let it pass while the real geometry did not.
 */
internal fun viewportLimitedDeadbandM(
    bandM: Float,
    viewportWidthPx: Int,
    metersPerDevicePx: Double,
): Float {
    if (viewportWidthPx <= 0 || !metersPerDevicePx.isFinite() || metersPerDevicePx <= 0.0) return bandM
    val visibleWidthM = viewportWidthPx * metersPerDevicePx
    return minOf(bandM, (BOUNDS_FIT_MARGIN_FRACTION * visibleWidthM).toFloat())
}

// ── Auto-zoom deadband ───────────────────────────────────────────────────────
// The centering deadband above stopped the map creeping; this stops it breathing.
// Auto-zoom fits a box around the phone and the rocket, so its input is the
// SEPARATION between them — and at recovery range that separation is mostly the
// two receivers disagreeing. Measured on a stationary locator: Dist read 6, 7 and
// 11 m within a couple of minutes, and the camera zoom swung 0.6 levels following
// it.
//
// The closest-zoom setting does not cover this, which is worth being precise
// about because it looks like it should. That limit binds how far IN auto-zoom
// may go; the swings measured here happen at and below it, where there is
// nothing to clamp. Recovering the zoom from the rendered scale bar across four
// captures gave 20.57, 20.46, 20.01 and 19.96 while Dist read 6, 7, 11 and 11 m
// — tracking continuously, which a clamped value cannot do.
//
// Band floor and ceiling, in zoom levels. The ceiling matters more than it looks:
// the honest statistics say that when separation and error are comparable the
// fitted zoom carries several levels of uncertainty and should never move at all,
// which would leave the map framed for 30 m while you stood 5 m away. Capping the
// band means the last stretch of an approach re-frames once rather than never.
internal const val AUTO_ZOOM_DEADBAND_MIN_LEVELS = 0.25f
internal const val AUTO_ZOOM_DEADBAND_MAX_LEVELS = 1.5f

/**
 * How far the fitted zoom may drift before the camera follows it, in zoom levels.
 *
 * Same shape as [recenterDeadbandM] — 2σ of the drift between two noisy samples —
 * but the σ is derived differently, and the difference is not cosmetic:
 *
 *  - **No halving.** The centering target is the midpoint between the two fixes,
 *    which moves half as far as they do. Separation is a *difference* between
 *    them, so both errors land on it at full weight: σ_separation =
 *    √(σ_locator² + σ_phone²).
 *  - **Converted through the log.** Zoom is logarithmic in separation, so a fixed
 *    error in meters is a large zoom error when the two are close together and a
 *    negligible one when they are far apart: σ_zoom = σ_separation / (D·ln2).
 *    This is what makes the band self-scaling — wide where the separation is
 *    mostly noise, narrow where it is real.
 *
 * @param separationM current distance between the two fixes. Non-positive or
 *   non-finite values yield the widest band, which is the right failure
 *   direction: an unknown separation is not evidence that the zoom should move.
 */
internal fun autoZoomDeadbandLevels(
    locatorHaccM: Float,
    trackerAccuracyM: Float?,
    separationM: Double,
): Float {
    fun sane(v: Float?) = if (v != null && v.isFinite() && v > 0f) v else 0f
    val locator = sane(locatorHaccM)
    val phone = sane(trackerAccuracyM)
    val sigmaSeparation = sqrt(locator * locator + phone * phone)
    if (!separationM.isFinite() || separationM <= 0.0) return AUTO_ZOOM_DEADBAND_MAX_LEVELS
    val sigmaZoom = sigmaSeparation / (separationM * ln(2.0)).toFloat()
    return (2f * sqrt(2f) * sigmaZoom)
        .coerceIn(AUTO_ZOOM_DEADBAND_MIN_LEVELS, AUTO_ZOOM_DEADBAND_MAX_LEVELS)
}

private const val landingAltitudeThreshold = 30
private const val minimumSpokenAGLVelocity = 2 * 9.8

// Continuous-announcement cadence and descent thresholds. Speech timing is driven from a
// fixed poll interval (not the TTS engine's isSpeaking flag) so behavior is consistent
// across phone hardware.
private const val announcementIntervalMillis = 500L          // poll cadence for continuous callouts
// Repeat cadence for the prepped-and-disarmed warning (#37). Long on purpose:
// the locator's buzzer carries the escalation, and the always-visible banner is
// what keeps the condition in front of the operator without nagging.
private const val padAlertRepeatMillis = 30_000L
private const val descentWarningIntervalMillis = 10000L      // minimum gap between descent warnings
private const val freefallDescentRate = 50f                  // m/s downward => still in freefall (pre-chute)
private const val minDescentRateForPrediction = 1f           // m/s, floor to avoid div-by-zero / noise
private const val landingLeadTimeSeconds = 3f                // announce landing this long before predicted touchdown
private const val linkLossTimeout = 3000L                    // telemetry gap (ms) called out as a lost link
private const val landingLinkLossTimeout = 5000L             // telemetry gap (ms) during descent that triggers the link-loss fallback

// ── Supporting types ──────────────────────────────────────────────────────────

/** A navigation drawer entry binding a label, icon, and destination screen. */
private data class DrawerItem(val labelRes: Int, val iconRes: Int, val screen: NavDestination)

/** Pairs a deployment channel's mode with its armed state for display. */
private data class ChannelConfig(val mode: DeployMode?, val isArmed: Boolean)

// ── Helper functions ──────────────────────────────────────────────────────────

/**
 * True when a reported coordinate is a real fix.
 *
 * A coordinate is usable only if finite and in range.  LatLngBounds.build()
 * throws IllegalArgumentException ("NaN > NaN") if any included point is NaN,
 * which crashes Compose during recomposition — so NaN must be filtered here,
 * not just the 0,0 null-island case that the locator reports before it has a fix.
 *
 * Shared by the camera framing and the speech announcer: a 0,0 placeholder fed
 * into a great-circle distance produces a plausible-looking number thousands of
 * kilometers wide, which the announcer would happily read out loud.
 */
private fun validLatLng(lat: Double, lon: Double) =
    lat.isFinite() && lon.isFinite() &&
    abs(lat) <= 90.0 && abs(lon) <= 180.0 &&
    (lat != 0.0 || lon != 0.0)

private fun LatLng.isFix() = validLatLng(latitude, longitude)

/**
 * Ground distance in meters between two coordinates.
 *
 * Equirectangular rather than haversine, deliberately: the camera controller asks
 * this on every display frame (~120/s measured) and only ever about separations
 * of a few tens of meters, where the two formulas agree to far less than a
 * millimeter. The great-circle version buys nothing here and costs three more
 * transcendentals per frame.
 *
 * The longitude difference is wrapped to ±180° so a pair straddling the
 * antimeridian measures the short way round rather than most of the way about
 * the planet — the same ±540 idiom the compass filter uses on bearings.
 */
/**
 * Ground meters per LOGICAL (dp) pixel at [zoom] and [latitude].
 *
 * 78271.516… = half the 256-px-tile constant: MapLibre reports zoom in the
 * 512-px-tile convention, so its meters per pixel at zoom z is half of Google
 * Maps' at the same z.
 *
 * **Logical, not device, pixels** — this is the unit MapLibre's zoom is defined
 * in, and the distinction is a factor of the display density (2.25 on a 360 dpi
 * phone, more on a denser one). Anything sizing itself against the screen in
 * device pixels wants [metersPerDevicePx]; mixing the two silently inflates
 * every distance, which is how the scale bar came to overstate by 2.4x and how
 * the centering deadband came to be checked against a viewport twice the size of
 * the real one.
 */
internal fun metersPerDp(zoom: Float, latitude: Double): Double =
    78271.51696 * cos(latitude * PI / 180.0) / 2.0.pow(zoom.toDouble())

/**
 * Ground meters per DEVICE pixel — what to use when comparing a real distance
 * against a real number of screen pixels. [density] is `LocalDensity.density`.
 */
internal fun metersPerDevicePx(zoom: Float, latitude: Double, density: Float): Double =
    metersPerDp(zoom, latitude) / density

// Share of each viewport dimension the bounds fit gives away as margin, so the
// two markers are never hard against an edge.
//
// A FRACTION, because the previous value was 300 device pixels on every side and
// absolute pixels mean different things on different screens: on a 1008 px-wide
// phone that surrendered 600 px, 60% of the width, before anything was framed —
// which is why the markers sat in a fifth of the screen with room to spare all
// round. It also has to clear the overlays that sit on top of the map (status
// card, stats panel, control column), and those are laid out in fractions of the
// viewport too, so a fraction is the unit that actually tracks them.
private const val BOUNDS_FIT_MARGIN_FRACTION = 0.14f

/**
 * Padding for `getCameraForLatLngBounds`, in device pixels, as
 * `[left, top, right, bottom]`.
 *
 * Sized from the viewport rather than fixed, and clamped so that a very small or
 * not-yet-measured viewport cannot ask for padding that meets in the middle —
 * the fit degenerates and zooms far out when it does, which looks exactly like
 * the bug this replaces.
 */
internal fun autoZoomPadding(viewportWidthPx: Int, viewportHeightPx: Int): IntArray {
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return intArrayOf(0, 0, 0, 0)
    val h = (viewportWidthPx * BOUNDS_FIT_MARGIN_FRACTION).toInt()
        .coerceAtMost(viewportWidthPx / 2 - 1).coerceAtLeast(0)
    val v = (viewportHeightPx * BOUNDS_FIT_MARGIN_FRACTION).toInt()
        .coerceAtMost(viewportHeightPx / 2 - 1).coerceAtLeast(0)
    return intArrayOf(h, v, h, v)
}

internal fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthR = 6378137.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLonDeg = ((lon2 - lon1 + 540.0) % 360.0) - 180.0
    val dLon = dLonDeg * PI / 180.0 * cos((lat1 + lat2) / 2.0 * PI / 180.0)
    return earthR * sqrt(dLat * dLat + dLon * dLon)
}

/**
 * The spoken position of the rocket relative to the launch point — " 250 meters
 * north of launch point." — or null when there is no fix worth quoting.
 *
 * Every spoken distance goes through here.  Null means either the locator says
 * its own fix is bad or one end of [vector] was a 0,0 placeholder, and callers
 * word the no-position case themselves rather than appending "location unknown"
 * to a sentence that was about to quote one.
 */
/**
 * Whether the rocket is FLYING — the predicate almost every piece of flight UI
 * actually wants, as distinct from whether it is armed.
 *
 * Those were the same thing until #36 let a disarmed locator fly. Several places
 * were gated on `armedState` and so went dead through a disarmed flight: the
 * stats panel, the ascent/descent callouts, the flight-event announcements and
 * the heads-up gauges. Armed-and-waiting still counts as in flight, so the armed
 * path behaves exactly as before.
 *
 * Defined once because the failure mode is silent — a stale copy of this test
 * does not break, it just quietly stops talking.
 */
private fun isInFlight(armedState: Boolean, state: RocketState): Boolean =
    armedState || state.flightState != FlightStates.WaitingLaunch

private fun launchRelativePhrase(state: RocketState, vector: Vector?): String? =
    // The range ceiling is checked here too, not just on the displayed figure: an
    // impossible distance read aloud as a recovery bearing is worse than the same
    // number sitting in a corner of the screen, because it is instruction rather
    // than readout. Callers word the no-position case themselves.
    if (state.gpsStatus == SensorHealth.Ok && vector != null &&
        distanceWithinRadioRange(vector.distance))
        " ${vector.distance} meters ${vector.ordinal} of launch point."
    else null

/**
 * Seconds until the rocket reaches the ground at its current descent rate, or
 * [Float.MAX_VALUE] when the rate is too small to divide by — a rate near zero is
 * noise or a rocket that isn't descending, not a landing about to happen.
 */
internal fun timeToGroundSeconds(aglM: Float, descentRateMs: Float): Float =
    if (descentRateMs > minDescentRateForPrediction) aglM / descentRateMs
    else Float.MAX_VALUE

/** True when the rocket is about to touch down according to live telemetry. */
internal fun landingImminent(aglM: Float, descentRateMs: Float): Boolean =
    aglM < landingAltitudeThreshold ||
            timeToGroundSeconds(aglM, descentRateMs) < landingLeadTimeSeconds

/**
 * True when the rocket must be on the ground despite nothing having been heard
 * from it.
 *
 * The link almost always dies before the landing does — the last few hundred
 * meters are where line of sight to a rocket across a field runs out — so a
 * landing callout that waits to *hear* the touchdown mostly never comes.  This
 * flies the rocket the rest of the way down on the last altitude and descent rate
 * it managed to send: once that much wall-clock has passed with no contact, it is
 * down, and the last known position is the one to walk toward.
 *
 * The [landingLinkLossTimeout] floor keeps a routine 3 s dropout from concluding
 * a flight, which is not a decision that can be taken back.
 */
internal fun landedThroughBlackout(aglM: Float, descentRateMs: Float, messageAgeMs: Long): Boolean =
    messageAgeMs >= landingLinkLossTimeout &&
            (landingImminent(aglM, descentRateMs) ||
                    messageAgeMs / 1000f >= timeToGroundSeconds(aglM, descentRateMs))

/** Returns the display string for a single deployment channel based on its mode.
 *  Format: "Ch \[n]: [DP|DB|MP|MB|NA] \[value]" */
private fun deployChannelText(channel: Int, mode: DeployMode?, config: LocatorConfig): String {
    val abbr = when (mode) {
        DeployMode.DroguePrimary -> "Drogue Prm "
        DeployMode.DrogueBackup  -> "Drogue Bkp "
        DeployMode.MainPrimary   -> "Main   Prm "
        DeployMode.MainBackup    -> "Main   Bkp "
        else                     -> "Unused"
    }
    val value = when (mode) {
        DeployMode.DroguePrimary ->
            " ${config.droguePrimaryDeployDelay / 10}.${config.droguePrimaryDeployDelay % 10} s"
        DeployMode.DrogueBackup ->
            " ${config.drogueBackupDeployDelay / 10}.${config.drogueBackupDeployDelay % 10} s"
        DeployMode.MainPrimary ->
            " ${config.mainPrimaryDeployAltitude} m"
        DeployMode.MainBackup ->
            " ${config.mainBackupDeployAltitude} m"
        else -> ""
    }
    return "Ch $channel: $abbr$value"
}

// Bounds-fitting is done by MapLibre's own getCameraForLatLngBounds (a pure query) rather
// than hand-rolled Mercator math: replicating the SDK's pixel-density and tile-size
// conventions is easy to get subtly wrong, and wrong framing is invisible until the points
// you were supposed to be tracking slide off screen.

// ── HomeScreen ────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: RocketViewModel = viewModel(),
    permissionsState: MultiplePermissionsState,
    textToSpeech: TextToSpeech?,
    onRescan: () -> Unit,
    onSnoozePadAlert: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current

    // Hold the screen on while the live map is up. A flight is minutes of watching
    // the map and listening to callouts without touching the phone, which is
    // exactly the input-idle the system screen timeout is built to catch — it was
    // blanking the display mid-flight.
    //
    // Scoped to this screen rather than the activity window. Held activity-wide it
    // also covered settings, flight profiles and map download, where the phone is
    // being actively used or left to grind through a long download, and where the
    // display — normally the largest single draw on the device — has no reason to
    // stay lit. Leaving the screen returns the device to its normal timeout, and
    // backgrounding the app does so too, exactly as before.
    val activityWindow = (context as? Activity)?.window
    DisposableEffect(activityWindow) {
        activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val bluetoothConnectionState = BluetoothManagerRepository.bluetoothConnectionState.collectAsState().value
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var isMapLoaded by remember { mutableStateOf(false) }
    // Satellite imagery + gesture settings are configured inside MapLibreMapView; the
    // old Google MapProperties/MapUiSettings holders are no longer needed.

    val receiverConfig by viewModel.remoteReceiverConfig.collectAsState()
    val locatorConfig by viewModel.remoteLocatorConfig.collectAsState()
    val rocketState by viewModel.rocketState.collectAsState()
    val receiverDeviceName = BluetoothManagerRepository.receiverDevice.collectAsState().value?.name
        ?.takeIf { it.isNotEmpty() } ?: receiverConfig.deviceName
    val armedState = BluetoothManagerRepository.armedState.collectAsState().value
    val padAlert = BluetoothManagerRepository.padAlert.collectAsState().value
    val padAlertSnoozeMinutes = BluetoothManagerRepository.padAlertSnoozeMinutes.collectAsState().value
    val locatorArmedMessageState = BluetoothManagerRepository.locatorArmedMessageState.collectAsState().value
    val orientation = LocalConfiguration.current.orientation
    val hasCompass = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS)

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    // ViewModel-scoped so the fix survives navigation to the flight profiles screen and back.
    val trackerLocation by viewModel.trackerLocation.collectAsState()
    // Stand-in used until the first fix so the map can render right away; its 0,0
    // coordinates read as "no tracker GPS" everywhere downstream (validLatLng).
    val fallbackTrackerLocation = remember { Location("fallback") }
    val locationPermissionState = permissionsState.permissions
        .find { it.permission == Manifest.permission.ACCESS_FINE_LOCATION }
    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { viewModel.updateTrackerLocation(it) }
            }
        }
    }
    DisposableEffect(locationPermissionState?.hasPermission) {
        if (locationPermissionState?.hasPermission == true) {
            // Seed from the cached fix so the map can render immediately instead of
            // waiting for the first live GPS update (which can take many seconds, or
            // never arrive indoors / with GPS off).
            // Read through the flow, not the captured composable value, so a fix that landed
            // between this call and its callback isn't overwritten by an older cached one.
            fusedLocationClient.lastLocation.addOnSuccessListener { cached ->
                if (viewModel.trackerLocation.value == null && cached != null)
                    viewModel.updateTrackerLocation(cached)
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L).build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
        onDispose { fusedLocationClient.removeLocationUpdates(locationCallback) }
    }

    val locatorLatLng = LatLng(rocketState.latitude, rocketState.longitude)
    var locatorGPSLock by remember { mutableStateOf(false) }
    LaunchedEffect(trackerLocation, locatorLatLng) {
        trackerLocation?.let {
            val vector = viewModel.locatorVector(LatLng(it.latitude, it.longitude), locatorLatLng)
            viewModel.updateLocatorVector(vector)
        }
        locatorGPSLock = locatorLatLng.latitude != 0.0 && locatorLatLng.longitude != 0.0
    }

    val distanceToLocator = viewModel.locatorDistance.collectAsState().value
    val azimuth = viewModel.handheldDeviceAzimuth.collectAsState().value
    val lastAzimuth = viewModel.lastHandheldDeviceAzimuth.collectAsState().value
    val handheldDevicePitch = viewModel.handheldDevicePitch.collectAsState().value
    val handheldCameraAzimuth = viewModel.handheldCameraAzimuth.collectAsState().value
    val locatorElevation = viewModel.locatorElevation.collectAsState().value
    val azimuthToLocator = viewModel.locatorAzimuth.collectAsState().value
    // Only UNRELIABLE disqualifies the heading — one level below what raises the
    // calibration prompt, because the two cost different things. Suppressing the
    // overlay is the more destructive act: it removes the thing the user is walking
    // by. Measured on a Pixel 9 Pro XL, a calibrated phone away from interference
    // rests at MEDIUM and never reaches HIGH, so gating on HIGH would have taken
    // the overlay away permanently. See compassNeedsCalibration in MapWithOverlays
    // for the warning threshold, and ADR-0023 for both.
    // `>` rather than `!=`: SENSOR_STATUS_NO_CONTACT sorts BELOW UNRELIABLE, so an
    // equality test would have read the worst state the API can report as usable.
    val compassUsable = hasCompass &&
        viewModel.compassAccuracy.collectAsState().value > SensorManager.SENSOR_STATUS_UNRELIABLE
    val lastMessageAge = System.currentTimeMillis() - rocketState.lastMessageTime
    // Distinct from the above, which ages ANY message. Battery levels ride only on
    // the pre-launch message, so once the locator switches to telemetry they stop
    // being refreshed and this is the clock that says so.
    val preLaunchDataAge = System.currentTimeMillis() - rocketState.lastPreLaunchDataTime
    val flightPath = viewModel.flightPath.collectAsState().value
    val isFlightPathRecording = viewModel.isFlightPathRecording.collectAsState().value
    // Whether the locator's reported position is one we can quote a distance or a
    // bearing from at all — both come out of the same vector.
    val locatorFixUsable = validLatLng(rocketState.latitude, rocketState.longitude) &&
        viewModel.locatorDistancePlausible.collectAsState().value

    // Headless composable: manages all flight-event speech announcements
    FlightSpeechAnnouncer(
        rocketState = rocketState,
        armedState = armedState,
        padAlert = padAlert,
        padAlertSnoozeMinutes = padAlertSnoozeMinutes,
        locatorConfig = locatorConfig,
        locatorLatLng = locatorLatLng,
        viewModel = viewModel,
        textToSpeech = textToSpeech,
    )

    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        CameraPreviewScreen(
            handheldCameraAzimuth, azimuthToLocator, handheldDevicePitch, locatorElevation,
            rocketSpeed = rocketState.velocity,
            rocketAttitude = rocketState.attitude,
            inFlight = isInFlight(armedState, rocketState),
            lastMessageAge = lastMessageAge,
            // Bearing and distance come out of the same vector, so a position the
            // distance test rejects aims the AR marker just as wrongly. An
            // uncalibrated compass breaks the other half of the same subtraction:
            // the marker lands on a patch of sky chosen by whatever iron is near
            // the phone, and looks exactly as confident as a good one.
            bearingValid = locatorFixUsable && compassUsable,
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = modifier
                        .height(IntrinsicSize.Min)
                        .width(IntrinsicSize.Max),
                    drawerContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    drawerShape = RoundedCornerShape(bottomEnd = 16.dp)
                ) {
                    AppDrawerContent(
                        bluetoothConnectionState = bluetoothConnectionState,
                        armedState = armedState,
                        locatorActive = lastMessageAge < messageTimeout,
                        onNavigate = { screen ->
                            scope.launch { drawerState.close() }
                            navController.navigate(screen.name)
                        }
                    )
                }
            }
        ) {
            var scaffoldSize by remember { mutableStateOf(IntSize(0, 0)) }
            @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
            Scaffold(
                modifier = Modifier.onGloballyPositioned { scaffoldSize = it.size },
                floatingActionButton = {},
            ) {
                // Render the map even before the first GPS fix.  Downstream code
                // treats a 0,0 location as "no tracker GPS" (validLatLng) and simply
                // omits the phone position from auto-zoom until a real fix arrives,
                // so the map (and its controls) appear immediately rather than
                // blocking on location the way an early-return null gate did.
                run {
                    MapWithOverlays(
                        trackerLocation = trackerLocation ?: fallbackTrackerLocation,
                        rocketState = rocketState,
                        armedState = armedState,
                        padAlert = padAlert,
                        padAlertSnoozeMinutes = padAlertSnoozeMinutes,
                        receiverDeviceName = receiverDeviceName,
                        locatorConfig = locatorConfig,
                        locatorArmedMessageState = locatorArmedMessageState,
                        bluetoothConnectionState = bluetoothConnectionState,
                        locatorGPSLock = locatorGPSLock,
                        isMapLoaded = isMapLoaded,
                        onMapLoaded = { isMapLoaded = true },
                        onRescan = onRescan,
                        onSnoozePadAlert = onSnoozePadAlert,
                        hasCompass = hasCompass,
                        azimuth = azimuth,
                        lastAzimuth = lastAzimuth,
                        handheldDevicePitch = handheldDevicePitch,
                        lastMessageAge = lastMessageAge,
                        preLaunchDataAge = preLaunchDataAge,
                        distanceToLocator = distanceToLocator,
                        viewModel = viewModel,
                        scaffoldSize = scaffoldSize,
                        textToSpeech = textToSpeech,
                        flightPath = flightPath,
                        onMenuClick = { scope.launch { drawerState.apply { if (isClosed) open() else close() } } },
                        modifier = modifier
                    )
                }
            }
        }
    }
}

// ── Flight speech announcer ───────────────────────────────────────────────────

/**
 * Headless composable that owns all flight-event speech announcement state and logic.
 * Emits no UI; call it once from HomeScreen to register the two announcement effects.
 */
@Composable
private fun FlightSpeechAnnouncer(
    rocketState: RocketState,
    armedState: Boolean,
    padAlert: PadAlertState,
    padAlertSnoozeMinutes: Int,
    locatorConfig: LocatorConfig,
    locatorLatLng: LatLng,
    viewModel: RocketViewModel,
    textToSpeech: TextToSpeech?,
) {
    var previousAGL by remember { mutableIntStateOf(0) }
    var apogeeSpoken by remember { mutableStateOf(false) }
    var launchedState by remember { mutableStateOf(false) }
    var droguePrimaryState by remember { mutableStateOf(false) }
    var drogueBackupState by remember { mutableStateOf(false) }
    var mainPrimaryState by remember { mutableStateOf(false) }
    var mainBackupState by remember { mutableStateOf(false) }
    var drogueDeploySpoken by remember { mutableStateOf(false) }
    var mainDeploySpoken by remember { mutableStateOf(false) }
    var landingSpoken by remember { mutableStateOf(false) }
    // Set once the rocket is down — reported landed, or dead-reckoned to the ground
    // during a link blackout.  Everything the locator has to say after that point is
    // history: the events it flew through while out of contact arrive in a burst the
    // moment the link returns, and announcing "main charge" over a rocket that is
    // already lying in a field is worse than saying nothing.
    var flightConcluded by remember { mutableStateOf(false) }
    var receivedState by remember { mutableStateOf(FlightStates.WaitingLaunch) }
    var noseoverTime by remember { mutableLongStateOf(0L) }
    var launchLocation by remember { mutableStateOf(LatLng(0.0, 0.0)) }

    // Null whenever either end of the vector is a 0,0 placeholder rather than a
    // real fix — a launch point captured with no GPS lock sits on null island, and
    // the great-circle distance to it is the ~12 000 km the announcer used to read
    // out as a recovery bearing.  Callers say "location unknown" instead.
    val vectorFromLaunch = if (launchLocation.isFix() && locatorLatLng.isFix())
        viewModel.locatorVector(launchLocation, locatorLatLng)
    else null

    // Announces discrete flight state transitions (apogee, drogue/main deploy, landing).
    LaunchedEffect(rocketState.flightState) {
        if (!isInFlight(armedState, rocketState) || rocketState.flightState <= receivedState) return@LaunchedEffect
        receivedState = rocketState.flightState

        // The locator's own landing detection ends the flight, and it is the
        // authority on the matter.  Handled FIRST, ahead of the per-event checks
        // below: a link that comes back after the rocket is already down delivers
        // the whole flight in one step, and running those checks on it read out
        // every charge and deployment the rocket flew through minutes earlier.
        if (rocketState.flightState >= FlightStates.Landed) {
            // Still worth saying once, if the blackout dead-reckoning below hasn't
            // already — this is the number the user walks toward.
            if (!landingSpoken) {
                textToSpeech?.speak(
                    "Landing${launchRelativePhrase(rocketState, vectorFromLaunch)
                        ?: ", location unknown."}",
                    TextToSpeech.QUEUE_FLUSH, null, null
                )
            }
            // Reset all announcement guards for the next flight
            previousAGL = 0
            launchedState = false
            apogeeSpoken = false
            droguePrimaryState = false
            drogueBackupState = false
            mainPrimaryState = false
            mainBackupState = false
            drogueDeploySpoken = false
            mainDeploySpoken = false
            landingSpoken = false
            flightConcluded = false
            receivedState = FlightStates.WaitingLaunch
            noseoverTime = 0
            launchLocation = LatLng(0.0, 0.0)
            return@LaunchedEffect
        }

        // Landing was already presumed from the last telemetry before the link
        // dropped, so anything arriving now describes a rocket that is on the
        // ground.  Stay quiet until the locator confirms Landed above.
        if (flightConcluded) return@LaunchedEffect

        if (rocketState.flightState >= FlightStates.Launched && !launchedState) {
            launchedState = true
            launchLocation = LatLng(rocketState.latitude, rocketState.longitude)
        }
        if (rocketState.flightState >= FlightStates.Noseover && noseoverTime == 0L) {
            noseoverTime = System.currentTimeMillis()
            if (!apogeeSpoken) {
                apogeeSpoken = true
                textToSpeech?.speak(
                    "Apogee, ${rocketState.altitudeAboveGroundLevel.toInt()} meters.",
                    TextToSpeech.QUEUE_ADD, null, null
                )
            }
        }
        if (rocketState.flightState >= FlightStates.DroguePrimaryEvent && !droguePrimaryState) {
            droguePrimaryState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.DroguePrimary && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.DroguePrimary && rocketState.channel2Fired)
                textToSpeech?.speak("Drogue charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.DrogueBackupEvent && !drogueBackupState) {
            drogueBackupState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.DrogueBackup && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.DrogueBackup && rocketState.channel2Fired)
                textToSpeech?.speak("Drogue backup charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.MainPrimaryEvent && !mainPrimaryState) {
            mainPrimaryState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.MainPrimary && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.MainPrimary && rocketState.channel2Fired)
                textToSpeech?.speak("Main charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
        if (rocketState.flightState >= FlightStates.MainBackupEvent && !mainBackupState) {
            mainBackupState = true
            if (locatorConfig.deploymentChannel1Mode == DeployMode.MainBackup && rocketState.channel1Fired ||
                locatorConfig.deploymentChannel2Mode == DeployMode.MainBackup && rocketState.channel2Fired)
                textToSpeech?.speak("Main backup charge.", TextToSpeech.QUEUE_ADD, null, null)
        }
    }

    // ── Prepped-and-disarmed alert (ADR-0021 Decision 5, #37) ────────────────
    // The locator judges the condition (vertical, still, e-matches wired, not
    // armed) at 20 Hz and broadcasts the verdict; this only reacts to it, so the
    // buzzer at the pad and the voice here can never disagree.
    //
    // Speaks once on the rising edge, then repeats on a fixed cadence while the
    // condition holds. The ESCALATION lives in the locator's buzzer, which gets
    // louder and more frequent; repeating that here as well would just be two
    // things shouting. The app's anti-habituation answer is the banner below —
    // permanently visible, and silent.
    //
    // Keyed on padAlert so leaving the condition cancels the loop and re-arms it.
    val padAlertSpeech = stringResource(R.string.pad_alert_speech)
    LaunchedEffect(padAlert) {
        // Voice only while actually alerting. A snoozed alert is shown, never spoken —
        // speaking through a snooze would make the control useless.
        if (padAlert != PadAlertState.Alerting) return@LaunchedEffect
        // QUEUE_FLUSH: this outranks whatever routine callout is mid-sentence.
        textToSpeech?.speak(padAlertSpeech, TextToSpeech.QUEUE_FLUSH, null, null)
        while (true) {
            delay(padAlertRepeatMillis)
            textToSpeech?.speak(padAlertSpeech, TextToSpeech.QUEUE_ADD, null, null)
        }
    }

    // Haptic channel for the same alert. A third independent path to the operator
    // after the locator's buzzer and the app's voice — and the one that still
    // works with the phone muted, in a pocket, or on a loud flight line, which is
    // exactly where the other two fail. Deliberately not gated on the voice
    // setting: someone who turned speech off is MORE reliant on this, not less.
    val alertContext = LocalContext.current
    val vibrator = remember(alertContext) {
        alertContext.getSystemService(Vibrator::class.java)
    }
    DisposableEffect(padAlert, vibrator) {
        if (padAlert == PadAlertState.Alerting && vibrator?.hasVibrator() == true) {
            // Two short pulses then a gap, repeating — a deliberate "something is
            // wrong" rhythm rather than the single buzz of an ordinary
            // notification, and it echoes the locator's doubled buzzer pattern.
            val timings = longArrayOf(0, 260, 140, 260, 2400)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
        }
        // Cancel on ANY exit — snoozed, armed, laid down, or the screen going
        // away. A haptic that outlives its cause is worse than none: it teaches
        // the operator the phone is broken rather than the rocket is unarmed.
        onDispose { vibrator?.cancel() }
    }

    // Continuous ascent/descent callouts, driven from a fixed-cadence poll loop rather than
    // the TTS engine's isSpeaking flag so the announcement rate is identical across phone
    // hardware. rememberUpdatedState keeps the long-running coroutine reading the latest
    // telemetry, and lets the loop detect a stale (lost) LoRa link even when no new messages
    // are arriving to drive a recomposition.
    val currentRocketState by rememberUpdatedState(rocketState)
    val currentVector by rememberUpdatedState(vectorFromLaunch)
    // Keyed on inFlight, not armedState: during a disarmed flight armedState
    // never changes, so an effect keyed on it would never start.
    val inFlight = isInFlight(armedState, rocketState)
    LaunchedEffect(inFlight) {
        if (!inFlight) return@LaunchedEffect
        var lastDescentWarningTime = 0L
        // Link and GPS health are edge-triggered: each says something when the
        // state changes, not while it persists, so a long dropout is one sentence
        // rather than a chant.  Both start unknown so arming into an already-bad
        // state doesn't blurt on the first poll — the map's "No GPS" banner is the
        // display for a condition that was there all along.
        var linkEverLive = false
        var linkLostSpoken = false
        var gpsOkLast: Boolean? = null
        while (true) {
            delay(announcementIntervalMillis)
            val state = currentRocketState
            val vector = currentVector
            val now = System.currentTimeMillis()
            val messageAge = now - state.lastMessageTime
            val descentRate = state.velNed.z   // NED Down component: positive while descending
            val linkLive = messageAge < linkLossTimeout
            val fromLaunch = launchRelativePhrase(state, vector)

            // Link health.  Announced only after the link has been live at least
            // once, so starting the app out of range is silence rather than a
            // report of something that was never there.
            if (linkLive) {
                linkEverLive = true
                if (linkLostSpoken) {
                    linkLostSpoken = false
                    textToSpeech?.speak("Telemetry restored.", TextToSpeech.QUEUE_ADD, null, null)
                }
            } else if (linkEverLive && !linkLostSpoken) {
                linkLostSpoken = true
                // In the air the last-known position is the part worth hearing; on
                // the pad or after landing it is just the number already on screen.
                val airborne = state.flightState > FlightStates.WaitingLaunch &&
                        state.flightState < FlightStates.Landed
                textToSpeech?.speak(
                    if (airborne && fromLaunch != null) "Telemetry lost. Last known$fromLaunch"
                    else "Telemetry lost.",
                    TextToSpeech.QUEUE_FLUSH, null, null
                )
            }

            // GPS health, reported only while the link is live: with no messages
            // arriving, the locator's last-sent gpsStatus is stale and says nothing
            // about whether it has a fix now.
            if (linkLive) {
                val gpsOk = state.gpsStatus == SensorHealth.Ok
                if (gpsOkLast != null && gpsOk != gpsOkLast) {
                    textToSpeech?.speak(
                        if (gpsOk) "GPS fix restored." else "GPS fix lost.",
                        TextToSpeech.QUEUE_ADD, null, null
                    )
                }
                gpsOkLast = gpsOk
            }

            // Ascent altitude callouts every 100 m during coast to apogee.
            if (state.flightState == FlightStates.Burnout) {
                val roundedAGL = (state.altitudeAboveGroundLevel / 100).toInt() * 100
                if (roundedAGL > previousAGL) {
                    if (state.velocity > minimumSpokenAGLVelocity)
                        textToSpeech?.speak("$roundedAGL meters.", TextToSpeech.QUEUE_ADD, null, null)
                    previousAGL = roundedAGL
                }
            }

            // Descent: periodic warnings while in freefall, then exactly one landing announcement.
            // Landing is predicted from the vertical descent rate (time-to-ground), with a floor
            // on AGL and a link-loss fallback so a landing is still reported if the link drops.
            if (state.flightState > FlightStates.Noseover && state.flightState.isAirborne() &&
                !landingSpoken) {
                val agl = state.altitudeAboveGroundLevel
                when {
                    landedThroughBlackout(agl, descentRate, messageAge) -> {
                        landingSpoken = true
                        flightConcluded = true
                        textToSpeech?.speak(
                            "Landing." + (fromLaunch?.let { " Last known$it" }
                                ?: " Location unknown."),
                            TextToSpeech.QUEUE_FLUSH, null, null
                        )
                    }
                    // Everything below needs a live link: altitude and descent rate out of a
                    // stale packet describe where the rocket was, not where it is.
                    !linkLive -> {}
                    landingImminent(agl, descentRate) -> {
                        landingSpoken = true
                        flightConcluded = true
                        textToSpeech?.speak(
                            "Landing${fromLaunch ?: ", location unknown."}",
                            TextToSpeech.QUEUE_FLUSH, null, null
                        )
                    }
                    descentRate >= freefallDescentRate &&
                            now - lastDescentWarningTime >= descentWarningIntervalMillis -> {
                        lastDescentWarningTime = now
                        textToSpeech?.speak(
                            "Descent warning, ${descentRate.toInt()} meters per second" +
                                    (fromLaunch ?: ""),
                            TextToSpeech.QUEUE_FLUSH, null, null
                        )
                    }
                }
            }

            // Physical deployment detections.  Suppressed once the rocket is down:
            // these flags arrive latched in the telemetry, so the first packet after
            // a blackout that outlasted the flight would otherwise report a main
            // deployment that happened on the way to a landing already announced.
            //
            // isAirborne() is load-bearing, not belt-and-braces. On reaching Landed
            // the discrete-event effect resets every announcement guard for the next
            // flight — including flightConcluded, drogueDeploySpoken and
            // mainDeploySpoken — while THIS loop is still polling the same Landed
            // telemetry, whose deploy bits are still latched true. Without the
            // airborne test the next 500 ms tick re-announces both deployments at
            // landing, having just been told it had never announced them.
            if (!flightConcluded && state.flightState.isAirborne()) {
                if (state.flightState >= FlightStates.DroguePrimaryEvent && !drogueDeploySpoken && state.drogueDeployDetected) {
                    drogueDeploySpoken = true
                    textToSpeech?.speak("Drogue deployed.", TextToSpeech.QUEUE_ADD, null, null)
                }
                if (state.flightState >= FlightStates.MainPrimaryEvent && !mainDeploySpoken && state.mainDeployDetected) {
                    mainDeploySpoken = true
                    drogueDeploySpoken = true
                    textToSpeech?.speak("Main deployed.", TextToSpeech.QUEUE_ADD, null, null)
                }
            }
        }
    }
}

// ── Drawer content ────────────────────────────────────────────────────────────

/**
 * Renders the navigation drawer menu items, adapting to Bluetooth connection
 * state, armed state, and whether a locator is active.
 */
@Composable
private fun AppDrawerContent(
    bluetoothConnectionState: BluetoothConnectionState,
    armedState: Boolean,
    locatorActive: Boolean,
    onNavigate: (NavDestination) -> Unit,
) {
    val items = buildList {
        add(DrawerItem(R.string.application_settings, R.drawable.settings_applications, NavDestination.AppSettings))
        if (bluetoothConnectionState == BluetoothConnectionState.Ready)
            add(DrawerItem(R.string.receiver_settings, R.drawable.radio, NavDestination.ReceiverSettings))
        if (locatorActive && !armedState) {
            add(DrawerItem(R.string.locator_settings, R.drawable.navigation, NavDestination.LocatorSettings))
            add(DrawerItem(R.string.flight_profiles, R.drawable.u_turn_right, NavDestination.FlightProfiles))
        }
        if (locatorActive && armedState)
            add(DrawerItem(R.string.deployment_test, R.drawable.bomb, NavDestination.DeploymentTest))
        // Last: site prep done at home on Wi-Fi, not something reached for at the pad, so it
        // sits below the entries that track what is currently connected and armed.
        add(DrawerItem(R.string.download_map, R.drawable.navigation, NavDestination.DownloadMap))
    }

    Column(modifier = Modifier.padding(0.dp)) {
        items.forEach { item ->
            NavigationDrawerItem(
                label = { Text(stringResource(item.labelRes), style = typography.titleLarge) },
                icon = { Icon(painterResource(item.iconRes), contentDescription = stringResource(item.labelRes)) },
                selected = false,
                onClick = { onNavigate(item.screen) }
            )
        }
    }
}

// ── Map with overlays ─────────────────────────────────────────────────────────

/**
 * Renders the satellite map plus all overlaid UI: compass, scale bar, map camera
 * controller, arm/zoom controls, Bluetooth status, GPS lock warnings, and the
 * draggable locator stats panel.
 */
@Composable
private fun MapWithOverlays(
    trackerLocation: Location,
    rocketState: RocketState,
    armedState: Boolean,
    padAlert: PadAlertState,
    padAlertSnoozeMinutes: Int,
    receiverDeviceName: String,
    locatorConfig: LocatorConfig,
    locatorArmedMessageState: LocatorMessageState,
    bluetoothConnectionState: BluetoothConnectionState,
    locatorGPSLock: Boolean,
    onRescan: () -> Unit,
    onSnoozePadAlert: () -> Unit,
    isMapLoaded: Boolean,
    onMapLoaded: () -> Unit,
    hasCompass: Boolean,
    azimuth: Float,
    lastAzimuth: Float,
    handheldDevicePitch: Float,
    lastMessageAge: Long,
    preLaunchDataAge: Long,
    distanceToLocator: Int,
    viewModel: RocketViewModel,
    scaffoldSize: IntSize,
    textToSpeech: TextToSpeech?,
    flightPath: List<PathPoint>,
    onMenuClick: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val isFlightPathRecording = viewModel.isFlightPathRecording.collectAsState().value
        // Downloaded archive record, if any, plus which track the map should draw.
        val archivedFlightPath = viewModel.archivedFlightPath.collectAsState().value
        val showArchivedPath = viewModel.showArchivedPath.collectAsState().value
        // Password gating: only the connected locator may be armed from the app.
        val locatorConnected = viewModel.locatorConnected.collectAsState().value
        // Warn from LOW downward — one step below the UNRELIABLE threshold that
        // suppresses the AR overlay, so the prompt arrives while the heading is
        // merely suspect rather than only once it has been given up on. Gated on
        // hasCompass so a device without a magnetometer, which cannot report
        // anything better, is not nagged to calibrate hardware it does not have.
        val compassAccuracy = viewModel.compassAccuracy.collectAsState().value
        val compassNeedsCalibration = hasCompass &&
            compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        // Red once the heading is bad enough that the AR overlay is withheld, so the
        // color change and the marker vanishing are the same event rather than two
        // unexplained ones. `<=` rather than `==` because NO_CONTACT sorts below
        // UNRELIABLE and is worse, not better.
        val compassSevere = compassAccuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE
        var autoTargetMode by remember { mutableStateOf(true) }
        var autoZoomMode by remember { mutableStateOf(true) }
        var compassEnabled by remember { mutableStateOf(true) }
        var showControls by remember { mutableStateOf(false) }
        // Hoisted so a tap anywhere on the map (onMapClick) can collapse the
        // status-panel action dropdown, not just the timeout or a second panel tap.
        var actionsExpanded by remember { mutableStateOf(false) }
        // Handle used only for pure camera queries (getCameraForLatLngBounds) — never to
        // move the camera from the controller.
        var mapLibre by remember { mutableStateOf<MapLibreMap?>(null) }
        var mapViewSize by remember { mutableStateOf(IntSize.Zero) }
        var tiltMode by remember { mutableStateOf(MapTiltMode.Flat) }

        val context = LocalContext.current
        // Live map uses whichever satellite provider the user selected in the download
        // screen, so downloaded offline regions (same source) render here.
        val styleJson = remember { MapProviderPrefs.get(context).styleJson(context) }
        // The closest zoom auto-zoom will frame to, from App Settings — see
        // resolveMapMaxZoom for why it exists (GPS error, not tile supply).
        val liveMapMaxZoom =
            resolveMapMaxZoom(viewModel.mapMaxZoom.collectAsState().value).toDouble()
        val locatorLatLng = LatLng(rocketState.latitude, rocketState.longitude)
        val rocketFresh = lastMessageAge < messageTimeout
        // Link age is checked first: if we are not hearing from the locator, its
        // last-reported gpsStatus is itself stale and cannot qualify anything.
        // Only with a live link does a non-Ok gpsStatus mean what it says — the
        // locator is talking to us and telling us its own fix is not current, so
        // the lat/lon in those packets is latched rather than tracking the rocket.
        val markerState = when {
            !rocketFresh -> RocketMarkerState.Stale
            rocketState.gpsStatus != SensorHealth.Ok -> RocketMarkerState.Degraded
            else -> RocketMarkerState.Live
        }
        val cameraState = remember {
            MapLibreCameraState(
                CamPos(
                    target = LatLng(trackerLocation.latitude, trackerLocation.longitude),
                    zoom = 12f,
                    tilt = 0f,
                    bearing = azimuth,
                )
            )
        }

        // One-shot recenter: the map now renders before the first GPS fix (initial
        // camera falls back to 0,0), and auto-target only kicks in once the rocket
        // has GPS.  So as soon as the phone's own position is known — and while the
        // rocket still has none — snap the camera to the phone once, mirroring the
        // pre-render behavior without reintroducing the blank-screen wait.
        var didInitialCenter by remember { mutableStateOf(false) }
        LaunchedEffect(isMapLoaded, trackerLocation.latitude, trackerLocation.longitude) {
            val trackerValid = trackerLocation.latitude != 0.0 || trackerLocation.longitude != 0.0
            val rocketValid = rocketState.latitude != 0.0 || rocketState.longitude != 0.0
            if (!didInitialCenter && isMapLoaded && trackerValid && !rocketValid) {
                cameraState.position = CamPos(
                    target = LatLng(trackerLocation.latitude, trackerLocation.longitude),
                    zoom = 12f,
                    tilt = 0f,
                    bearing = azimuth,
                )
                didInitialCenter = true
            }
        }

        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
            styleJson = styleJson,
            cameraState = cameraState,
            rocketLatLng = locatorLatLng,
            markerState = markerState,
            accuracyRadiusM = rocketState.hacc.toDouble(),
            // Altitude and capture time are carried through, not dropped: they
            // drive the 3D altitude curtain and its one-second markers.
            //
            // The archived track substitutes for the live one rather than drawing
            // alongside it: they are the same quantity measured two ways (EKF vs
            // raw GPS), so overlaying them at the same color would read as one
            // noisy path rather than two estimates.
            flightPath = if (showArchivedPath && archivedFlightPath.isNotEmpty())
                archivedFlightPath
            else
                flightPath,
            userLocation = trackerLocation,
            onMapLoaded = onMapLoaded,
            onMapClick = {
                showControls = !showControls
                actionsExpanded = false
            },
            onMapReady = { mapLibre = it },
            // Captured, not discarded: the bounds fit needs real viewport pixels to
            // size its padding against. See autoZoomPadding.
            onSizeChanged = { mapViewSize = it },
        )

        MapCameraController(
            map = mapLibre,
            mapViewSize = mapViewSize,
            trackerLocation = trackerLocation,
            rocketState = rocketState,
            cameraState = cameraState,
            isMapLoaded = isMapLoaded,
            hasCompass = hasCompass,
            compassEnabled = compassEnabled,
            azimuth = azimuth,
            lastAzimuth = lastAzimuth,
            handheldDevicePitch = handheldDevicePitch,
            autoTargetMode = autoTargetMode,
            autoZoomMode = autoZoomMode,
            maxZoom = liveMapMaxZoom.toFloat(),
            tiltMode = tiltMode,
            onBearingUpdate = { viewModel.updateLastHandheldDeviceAzimuth(it) },
        )

        // Compass: positioned above the scale bar at bottom-left, 8 dp from left edge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 8.dp, y = (-96).dp)
                .size(60.dp)
                .background(mapOverlayBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.compass),
                contentDescription = "Compass",
                modifier = Modifier
                    .size(48.dp)
                    .rotate(-azimuth),
            )
        }
        // The heading cannot be repaired from here — the fusion owns the
        // magnetometer and there is no path into its calibration — so the indicator
        // names the one thing that does fix it: the figure-eight, drawn as the
        // gesture itself rather than described in words.
        //
        // It replaced the text "Compass off / figure-8 to fix", which read as though
        // the compass had been SWITCHED off — a plausible misreading, and one that
        // sends the user hunting for a setting to turn back on. The symbol cannot be
        // misread that way because it does not assert anything; it is a picture of
        // the motion to make. What it gives up is self-evidence, so it carries a
        // contentDescription and §9.3 of the manual carries the meaning.
        //
        // Sited against the compass rose rather than centre screen because it
        // qualifies that rose, and because the centre is reserved for the pad alert.
        if (compassNeedsCalibration) {
            Text(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 74.dp, y = (-112).dp)
                    // The glyph alone reads as "infinity" to a screen reader, which
                    // is not what it means here.
                    .semantics {
                        contentDescription = if (compassSevere)
                            "Compass unreliable — sweep the phone in a figure-eight to recalibrate"
                        else
                            "Compass disturbed — sweep the phone in a figure-eight to recalibrate"
                    },
                text = "∞",
                color = if (compassSevere) Color.Red else Color.Yellow,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        val density = LocalDensity.current
        val scaleBarMaxWidth = with(density) {
            // Cap at 42 % of scaffold width minus the 8 dp left offset and 8 dp right clearance
            (scaffoldSize.width * 45 / 100 - 8.dp.roundToPx() - 8.dp.roundToPx()).toDp()
                .coerceAtMost(192.dp)
                .coerceAtLeast(48.dp)
        }
        GenericScaleBar(
            cameraState = cameraState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 8.dp, y = (-36).dp),
            width = scaleBarMaxWidth,
            barColor = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.secondary,
        )

        if (lastMessageAge < messageTimeout) {
            // A plain "Disarmed" is correct on the bench and near-invisible at the
            // pad, which is where it matters. When the locator reports a prepped
            // rocket standing disarmed (#37) the same indicator escalates: it says
            // what is wrong rather than just what is true, and turns red. This is
            // the app's anti-habituation answer — permanently visible and silent,
            // so the voice can stay on a long cadence (ADR-0021 Decision 5).
            PulsingText(
                modifier = modifier
                    .align(Alignment.Center),
                // Pulse ONLY once the pad alert has escalated this banner. A
                // disarmed rocket and a missing GPS lock are ordinary pre-flight
                // states — true, worth showing, and not worth an animation. They
                // are also the states the app sits in for most of its working
                // life, and pulsing through all of it both spent the battery and
                // trained the eye to ignore the very thing the escalation needs it
                // to notice. The color already carries the distinction; the motion
                // now agrees with it.
                pulse = padAlert != PadAlertState.Quiet,
                text = (if (padAlert == PadAlertState.Alerting) stringResource(R.string.pad_alert_banner)
                        else if (padAlert == PadAlertState.Snoozed) stringResource(R.string.pad_alert_snoozed, padAlertSnoozeMinutes)
                        else if (!armedState) "Disarmed" else "") +
                        (if (!armedState && !locatorGPSLock) "\n" else "") +
                        (if (!locatorGPSLock) "No GPS" else ""),
                color = when (padAlert) {
                    PadAlertState.Alerting -> Color.Red
                    PadAlertState.Snoozed  -> Color.Yellow
                    else                   -> Color.White
                },
                textAlign = TextAlign.Center,
                style = typography.displayLarge,
            )
        } else if (bluetoothConnectionState == BluetoothConnectionState.Ready) {
            PulsingText(
                modifier = modifier
                    .align(Alignment.Center),
                // Static, by the same rule: pulsing means the pad alert escalated,
                // and nothing else. A lost link is already unmissable — the whole
                // stats panel goes with it — so the motion would be competing with
                // the escalation it is reserved for.
                pulse = false,
                text = "No Locator",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = typography.displayLarge,
            )
        }

        // Top Row: menu button | status (centered) | view controls — 8 dp border everywhere
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Menu button — primaryContainer background + onPrimaryContainer icon matches
            // the original ExtendedFloatingActionButton appearance.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            // Status area — centered in available space
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                MapControlsColumn(
                    bluetoothConnectionState = bluetoothConnectionState,
                    lastMessageAge = lastMessageAge,
                    preLaunchDataAge = preLaunchDataAge,
                    rocketState = rocketState,
                    receiverDeviceName = receiverDeviceName,
                    locatorConfig = locatorConfig,
                    armedState = armedState,
                    padAlert = padAlert,
                    padAlertSnoozeMinutes = padAlertSnoozeMinutes,
                    locatorArmedMessageState = locatorArmedMessageState,
                    onToggleArmed = { viewModel.updateArmedState() },
                    onRescan = onRescan,
                    onSnoozePadAlert = onSnoozePadAlert,
                    textToSpeech = textToSpeech,
                    locatorConnected = locatorConnected,
                    actionsExpanded = actionsExpanded,
                    onActionsExpandedChange = { actionsExpanded = it },
                    modifier = Modifier,
                )
            }
            // View-mode and auto-framing controls
            Column(
                modifier = Modifier
                    .background(mapOverlayBg, RoundedCornerShape(12.dp))
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Cycles Flat -> Altitude -> FollowDevice. The icon shows the mode currently
                // in effect; the description names the one a tap will switch to.
                IconButton(
                    onClick = { tiltMode = tiltMode.next() },
                    modifier = Modifier.size(48.dp),
                ) {
                    val nextMode = tiltMode.next()
                    val switchTo = when (nextMode) {
                        MapTiltMode.Flat -> "2D view"
                        MapTiltMode.Altitude -> "3D view"
                        MapTiltMode.FollowDevice -> "phone-tilt view"
                    }
                    if (tiltMode == MapTiltMode.FollowDevice) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "Switch to $switchTo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                    } else {
                        Image(
                            painter = painterResource(
                                id = if (tiltMode == MapTiltMode.Altitude) R.drawable.ic_view_3d
                                else R.drawable.ic_view_2d
                            ),
                            contentDescription = "Switch to $switchTo",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { autoTargetMode = !autoTargetMode },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = if (autoTargetMode) "Disable auto-center" else "Enable auto-center",
                        tint = if (autoTargetMode) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                IconButton(
                    onClick = { autoZoomMode = !autoZoomMode },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomOutMap,
                        contentDescription = if (autoZoomMode) "Disable auto-zoom" else "Enable auto-zoom",
                        tint = if (autoZoomMode) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                IconButton(
                    onClick = { compassEnabled = !compassEnabled },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = if (compassEnabled) "Disable magnetic orientation" else "Enable magnetic orientation",
                        tint = if (compassEnabled) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                IconButton(
                    onClick = {
                        if (isFlightPathRecording) viewModel.stopFlightPathRecording()
                        else viewModel.startFlightPathRecording()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (isFlightPathRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = if (isFlightPathRecording) "Stop recording flight path" else "Start recording flight path",
                        tint = if (isFlightPathRecording) Color.Red else Color.White.copy(alpha = 0.35f),
                    )
                }
                // Only offered once a record has been downloaded — otherwise there
                // is no archived track to switch to and the control would be a
                // dead button.
                if (archivedFlightPath.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.toggleArchivedPath() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = if (showArchivedPath)
                                "Show live GPS path" else "Show archived (fused) path",
                            tint = if (showArchivedPath) Color(COLOR_ARCHIVED_ACTIVE)
                                   else Color.White.copy(alpha = 0.35f),
                        )
                    }
                }
                IconButton(
                    onClick = { viewModel.resetFlightPath() },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset flight path",
                        tint = Color.White,
                    )
                }
            }
        }

        if (lastMessageAge < messageTimeout) {
            LocatorStats(
                rocketState = rocketState,
                armedState = armedState,
                distanceToLocator = distanceToLocator,
                locatorConfig = locatorConfig,
                viewModel = viewModel,
                scaffoldSize = scaffoldSize,
                textToSpeech = textToSpeech,
                modifier = modifier,
            )
        }
    }
}

// ── Map camera controller ─────────────────────────────────────────────────────

/**
 * What drives the map camera's tilt. Cycled by the view button in the map's control column.
 */
enum class MapTiltMode {
    /** Flat, straight down. */
    Flat,

    /** Tilted toward the horizon, opening up as the rocket climbs. */
    Altitude,

    /** Tilt follows the physical pitch of the phone — raise it to look toward the horizon. */
    FollowDevice;

    fun next(): MapTiltMode = entries[(ordinal + 1) % entries.size]
}

// |pitch| at or above this reads as "lying flat" and maps to a top-down view. Doubles as the
// dead zone: the last 10° before flat all resolve to 0 rather than hunting near it.
private const val TILT_FOLLOW_FLAT_DEG = 80f

/**
 * Map the phone's physical pitch to a camera tilt for [MapTiltMode.FollowDevice].
 *
 * `handheldDevicePitch` is the elevation of the device's screen-normal axis. Measured on a
 * Pixel 9 Pro XL: ~−88° lying flat (normal pointing at the zenith), ~−7° held upright. Only
 * the magnitude is used, so the mapping doesn't depend on the sign convention — and leaning
 * past vertical eases the tilt back down symmetrically rather than jumping.
 *
 * Flat reads as top-down (0°); raising the phone toward vertical opens the view out to
 * MapLibre's 60° ceiling. Smoothing is left to the caller's Kalman tilt filter.
 */
private fun tiltFromDevicePitch(pitchDeg: Float): Float {
    val fromUpright = abs(pitchDeg).coerceIn(0f, 90f)
    if (fromUpright >= TILT_FOLLOW_FLAT_DEG) return 0f
    return ((1f - fromUpright / TILT_FOLLOW_FLAT_DEG) * MAPLIBRE_MAX_PITCH)
        .coerceIn(0f, MAPLIBRE_MAX_PITCH)
}

/**
 * The camera filter's working state, held deliberately as PLAIN FIELDS rather
 * than Compose snapshot state.
 *
 * This is the whole point of the frame-loop rewrite. These values are written on
 * every display frame, and as `mutableStateOf` each write invalidated the
 * composable that had just read them — a self-sustaining loop that recomposed the
 * camera controller at display rate forever, whether or not anything had moved.
 * Measured at 120 fps on a still map with nothing connected.
 *
 * The filters converge asymptotically, so they never stop emitting marginally
 * different values and the loop never ran out of fuel. Ordinary snapshot state is
 * the wrong tool for something ticked by the clock rather than by data: nothing
 * outside the tick reads these, so nothing should be woken by them.
 */
private class CameraFilterState {
    var lastUserGestureTime = 0L
    var smoothedTarget = LatLng(0.0, 0.0)
    var smoothedZoom = 12f
    var smoothedTilt = 0f
    /**
     * The center the camera is actually filtering toward — re-latched from the
     * live auto-center target only when that target has drifted past the
     * GPS-error deadband. Null means "no anchor yet", which latches on the next
     * frame that has a target: at startup, after a gesture, and whenever a camera
     * control is tapped. See where recenterDeadbandM is applied.
     */
    var anchorTarget: LatLng? = null
    /**
     * The zoom's equivalent, in zoom levels. Holds the UNCLAMPED fit — see where
     * autoZoomDeadbandLevels is applied for why it is not the clamped value.
     */
    var anchorZoom: Float? = null
}

/**
 * Headless composable that keeps the map camera smoothly framing both the tracker
 * and the rocket using a Kalman filter. Backs off for 5 s after a user gesture
 * so that manual panning is not immediately overridden.
 *
 * The filter is ticked from a [withFrameNanos] loop, NOT from composition. It has
 * to run every frame — that is what makes the motion smooth — but running it *as*
 * composition meant every frame was also a recomposition, forever. Composition now
 * happens only when the inputs actually change (roughly once per fix), and the
 * per-frame work touches no snapshot state except the camera position, which
 * carries its own sub-perceptual deadband.
 *
 * When tilted (see [MapTiltMode]):
 * - Tilt is merged into the final CameraPosition so only one native map move occurs per frame.
 * - Tilt resumes immediately after a pan/zoom gesture while target/zoom still defer 5 s.
 * - Zoom is corrected for perspective foreshortening at high tilt.
 *
 * Tilt is never user-driven by gesture: tilt gestures are disabled on the map (the
 * control is the mode button), so the camera owns tilt outright.
 */
@Composable
private fun MapCameraController(
    map: MapLibreMap?,
    // Real viewport pixels, for sizing the bounds-fit padding. Zero until the map
    // view has been measured, which autoZoomPadding handles.
    mapViewSize: IntSize,
    trackerLocation: Location,
    rocketState: RocketState,
    cameraState: MapLibreCameraState,
    isMapLoaded: Boolean,
    hasCompass: Boolean,
    compassEnabled: Boolean,
    azimuth: Float,
    lastAzimuth: Float,
    handheldDevicePitch: Float,
    autoTargetMode: Boolean,
    autoZoomMode: Boolean,
    // Closest zoom auto-zoom may frame to. Enforced here and nowhere else — the
    // map sets no max-zoom preference, so pinch is unaffected. See where
    // smoothedZoom is clamped.
    maxZoom: Float,
    tiltMode: MapTiltMode,
    onBearingUpdate: (Float) -> Unit,
) {
    // For converting the camera zoom into ground meters per DEVICE pixel, which is
    // what the viewport-relative deadband cap has to reason in.
    val displayDensity = LocalDensity.current.density

    val kalmanGainTarget  = 0.1f
    val kalmanGainZoom    = 0.05f
    val kalmanGainTilt    = 0.05f
    val kalmanGainBearing = 0.01f

    val filter = remember { CameraFilterState() }

    val rocketHasGps  = validLatLng(rocketState.latitude, rocketState.longitude)
    val trackerHasGps = validLatLng(trackerLocation.latitude, trackerLocation.longitude)

    // Hoisted out of the tick because remember{} is a composition tool and the tick
    // is not composition. Position is unchanged: a native JNI query cached on the
    // two fixes, so it re-runs about once a second rather than once a frame.
    //
    // Ask the SDK to compute the framing. getCameraForLatLngBounds is a PURE query — it
    // returns a CameraPosition without touching the map — so unlike the old
    // moveCamera(newLatLngBounds(...)) "probe" it cannot move the camera mid-frame (that
    // was the auto-zoom wobble: two native moves per frame, drawn by the continuously
    // rendering GL thread).
    //
    // It also beats hand-rolled Mercator math, which has to get the pixel-density and
    // tile-size conventions exactly right to land the fit — and silently mis-frames when
    // it doesn't.
    //
    // Fit NORTH-UP and FLAT (bearing 0, tilt 0), matching what Google's newLatLngBounds
    // always did. The default overload fits for the *current* bearing/tilt, which zooms
    // out further — a rotated box needs a bigger viewport, and with the compass on the
    // bearing is arbitrary. Worse, tilt is already compensated below (zoomCorrection),
    // so letting the SDK account for it too corrects twice and over-zooms out.
    //
    // Bounds need BOTH points. MapLibre's LatLngBounds.Builder — unlike the Google Maps
    // builder it replaced — throws InvalidLatLngBoundsException from build() when only one
    // point was included. The tracker has no fix for the first moments after the map is
    // re-created (e.g. returning here from flight profiles), so that is a routine state,
    // not an edge case: with only the rocket known, center on it and don't touch the zoom.
    val boundsCam = remember(
        rocketState.latitude, rocketState.longitude,
        trackerLocation.latitude, trackerLocation.longitude,
        // Viewport included: the padding is derived from it, so a rotation or
        // any resize has to re-ask for the fit rather than keep one computed
        // against the old dimensions.
        rocketHasGps, trackerHasGps, map, mapViewSize,
    ) {
        if (!trackerHasGps || !rocketHasGps) null
        else {
            val bounds = LatLngBounds.Builder()
                .include(LatLng(rocketState.latitude, rocketState.longitude))
                .include(LatLng(trackerLocation.latitude, trackerLocation.longitude))
                .build()
            map?.getCameraForLatLngBounds(
                bounds,
                autoZoomPadding(mapViewSize.width, mapViewSize.height),
                0.0, 0.0,
            )
        }
    }

    // Tapping a camera control is an explicit command, not something to defer to.
    //
    // The gesture backoff exists so the auto-camera doesn't fight your fingers,
    // but its early-return blocks EVERY camera change — so after a manual
    // pan/zoom/rotate, hitting 3D (or auto-center, auto-zoom, compass) did
    // nothing at all until the 5 s window expired.
    //
    // The anchor is dropped for the same reason: re-enabling auto-center means
    // "center on it now", and an anchor left over from before the toggle would
    // hold the camera off-target until GPS noise happened to cross the deadband.
    LaunchedEffect(tiltMode, autoTargetMode, autoZoomMode, compassEnabled) {
        filter.lastUserGestureTime = 0L
        filter.anchorTarget = null
        filter.anchorZoom = null
    }

    // One frame's worth of filtering. Held through rememberUpdatedState so the
    // frame loop below always calls the latest version — closing over the current
    // composition's inputs — without the loop being torn down and restarted every
    // time one of them changes.
    val tick by rememberUpdatedState(fun CameraFilterState.() {
        if (!isMapLoaded) return

        // Gesture state is read here rather than collected through a snapshotFlow.
        // The flow keyed on `isGesturing to position` re-emitted on every camera
        // frame and allocated a Pair each time; polling the same latched flag once
        // per frame is equivalent and free.
        //
        // ONLY isGesturing. moveStartedReason must not be consulted: it is
        // stale-sticky — a real gesture sets it to REASON_GESTURE and nothing
        // clears it — so a long-finished gesture could re-arm the backoff one
        // frame after it was cleared, leaving the controller a single frame of
        // camera motion per 5 s cycle: tilt froze part-way, each isolated write
        // showed as a jump, and auto-zoom crawled. isGesturing is latched on a
        // real gesture and cleared on camera idle, so it cannot go stale.
        //
        // Polled every frame, which is what keeps lastUserGestureTime rolling
        // forward for the whole duration of a continuous gesture (long pan, slow
        // pinch) so the 5 s recovery window starts from the last frame of input.
        if (cameraState.isGesturing) {
            lastUserGestureTime = System.currentTimeMillis()
        }

        val now = System.currentTimeMillis()
        val userGestureRecent = now - lastUserGestureTime <= 5000

        // Compute the target tilt for this frame.
        // Tilt: 0° = straight down, larger = closer to the horizon. MapLibre's hard ceiling is
        // MapLibreConstants.MAXIMUM_PITCH = 60°, and it silently clamps anything above that —
        // so the range must live entirely below 60, not straddle it. (The pre-MapLibre code
        // ramped 60→67.5° against Google's 67.5° limit; ported over, every value above 60 was
        // clamped away and the tilt sat at a constant 60°.)
        // Open up toward the horizon as the rocket climbs: 45° on the pad, reaching the 60°
        // ceiling at 450 m AGL.
        val targetTilt = when (tiltMode) {
            MapTiltMode.FollowDevice -> tiltFromDevicePitch(handheldDevicePitch)
            MapTiltMode.Altitude ->
                (45f + rocketState.altitudeAboveGroundLevel / 30f).coerceIn(45f, MAPLIBRE_MAX_PITCH)
            MapTiltMode.Flat -> 0f
        }
        // During a gesture (pan, zoom, rotate) sync the smoothed state from the native camera so
        // that Kalman resumes from the actual position when the gesture window expires.
        // Outside gestures, filter smoothedTilt toward targetTilt.
        if (userGestureRecent) {
            smoothedTarget = cameraState.position.target
            smoothedZoom   = cameraState.position.zoom + (smoothedTilt / 90f * 1.5f)
            smoothedTilt   = cameraState.position.tilt
            // Drop the anchor along with the rest of the filter state. A pan moves the
            // camera to somewhere the anchor knows nothing about, so keeping it would
            // mean auto-center resumed by measuring the deadband from a stale point:
            // pan a short way and the drift back would never trip, leaving the map
            // parked off the rocket with no way to explain it.
            anchorTarget = null
            anchorZoom = null
            onBearingUpdate(cameraState.position.bearing)
            return   // leave the camera untouched so gestures work freely in every tilt mode
        }
        smoothedTilt += (targetTilt - smoothedTilt) * kalmanGainTilt
        // zoomCorrection is driven by smoothedTilt so it fades in/out with the tilt transition
        // rather than snapping to 0 the instant the tilt mode changes.
        val zoomCorrection = smoothedTilt / 90f * 1.5f

        // No recent gesture — safe to use the computed ideal bounds.
        // Only include the rocket if it has a valid GPS fix — excluding 0,0 prevents the bounds
        // from spanning to null-island and pulling the Kalman zoom filter toward world level.
        val (autoTarget, autoZoom) = if ((autoTargetMode || autoZoomMode) && rocketHasGps) {
            val rocketLatLng = LatLng(rocketState.latitude, rocketState.longitude)
            if (trackerHasGps) Pair(boundsCam?.target, boundsCam?.zoom?.toFloat())
            else Pair(rocketLatLng, null)
        } else {
            Pair(null, null)
        }

        // Drive the Kalman state (smoothedTarget / smoothedZoom) toward the ideal auto values.
        // Use the remembered smoothed values as the Kalman base — not cameraPositionState.position
        // — so that the zoom correction applied at the end doesn't feed back into the next frame.
        //
        // The filter follows the ANCHOR, not the live target. Re-latch the anchor only
        // once the live target has drifted past what the two receivers' combined error
        // can account for; inside that distance the anchor does not move, the filter
        // has nothing to converge toward, and the map is genuinely still.
        //
        // Deadbanding the anchor rather than the filter output is what makes it settle.
        // The obvious alternative — skip the filter step whenever the live target is
        // within the deadband of the CAMERA — never converges: the camera creeps
        // toward the target, re-enters the deadband a full deadband short of it, and
        // stops there, so it permanently trails the rocket by up to that distance and
        // stutters along the boundary as noise pushes it in and out. Against a latched
        // anchor the filter always has a fixed point to reach, reaches it, and stops.
        //
        // Only the framing that the phone contributes to counts as a midpoint; with no
        // tracker fix the target is the rocket alone and carries its full error.
        if (autoTargetMode && autoTarget != null) {
            val deadbandM = viewportLimitedDeadbandM(
                recenterDeadbandM(
                    rocketState.hacc,
                    if (trackerHasGps) trackerLocation.accuracy else null,
                ),
                mapViewSize.width,
                // The zoom actually applied to the camera, which is the post-correction
                // one — that is what determines how much ground is on screen.
                metersPerDevicePx(
                    smoothedZoom - zoomCorrection,
                    smoothedTarget.latitude,
                    displayDensity,
                ),
            )
            val anchor = anchorTarget
            if (anchor == null ||
                metersBetween(
                    anchor.latitude, anchor.longitude,
                    autoTarget.latitude, autoTarget.longitude,
                ) > deadbandM
            ) {
                anchorTarget = autoTarget
            }
        }
        val followTarget = anchorTarget
        smoothedTarget = if (autoTargetMode && followTarget != null)
            LatLng(
                smoothedTarget.latitude  + (followTarget.latitude  - smoothedTarget.latitude)  * kalmanGainTarget,
                smoothedTarget.longitude + (followTarget.longitude - smoothedTarget.longitude) * kalmanGainTarget,
            )
        else smoothedTarget

        // The closest-zoom limit lives HERE, on the filter, and nowhere else. The map
        // sets no max-zoom preference, so a pinch can always go closer than this —
        // only auto-zoom is bound by it, which is the point of the setting.
        //
        // Inside the autoZoomMode branch deliberately. Applied unconditionally it
        // would also claw back a manual zoom while auto-zoom is switched OFF: the
        // gesture branch above seeds the filter from the native camera, so a pinch
        // past the limit would be undone the moment the gesture window expired, with
        // nothing on screen to explain it.
        //
        // getCameraForLatLngBounds is free to ask for a zoom well past the limit, and
        // does exactly that when the two fixes are nearly coincident — walking up to a
        // landed rocket. That separation is mostly GPS error, so the request swings
        // hard from fix to fix. Clamping as it is filtered keeps those swings out of
        // the stored state: unclamped, the filter tracks them above the limit and
        // looks calm only until a swing drops back below it and the map lurches. It
        // also avoids the unwind lag, where a filter wound up to an unreachable value
        // must come back down at 5 % per frame before the map visibly starts zooming
        // out as you walk away.
        //
        // The ceiling carries + zoomCorrection because the correction is subtracted on
        // the way to the camera: this bounds the zoom actually applied at maxZoom
        // rather than the pre-correction state, which under tilt would lose up to a
        // whole level of legitimate range. It matches the formula the gesture branch
        // uses to read state back from the native camera.
        //
        // The zoom follows an ANCHOR for the same reason the target does, and the
        // limit above is not a substitute for it: the limit binds how far IN the fit
        // may go, while these swings happen at and below it, where there is nothing
        // to clamp. Measured on a stationary locator, the fit moved 0.6 levels while
        // Dist read 6, 7 and 11 m — all of it the two receivers disagreeing.
        //
        // Compared in ZOOM space rather than meters of separation. The two are not
        // interchangeable: zoom is logarithmic in separation, so the same few meters
        // of error is most of a zoom level when the fixes are close together and
        // nothing at all when they are far apart. Deadbanding the separation directly
        // would need a band wider than the separation itself at recovery range, which
        // is the regime this exists for.
        //
        // The anchor is the UNCLAMPED fit, while the filter output is clamped to the
        // limit. Comparing like with like is the point: clamping the anchor too would
        // make every fit past the limit compare equal, so a genuine move further in
        // could never re-latch once one had.
        if (autoZoomMode && autoZoom != null) {
            val separationM =
                if (rocketHasGps && trackerHasGps) metersBetween(
                    rocketState.latitude, rocketState.longitude,
                    trackerLocation.latitude, trackerLocation.longitude,
                ) else 0.0
            val bandLevels = autoZoomDeadbandLevels(
                rocketState.hacc,
                if (trackerHasGps) trackerLocation.accuracy else null,
                separationM,
            )
            val anchor = anchorZoom
            if (anchor == null || abs(autoZoom - anchor) > bandLevels) {
                anchorZoom = autoZoom
            }
        }
        val followZoom = anchorZoom
        smoothedZoom = if (autoZoomMode && followZoom != null)
            (smoothedZoom + (followZoom - smoothedZoom) * kalmanGainZoom)
                .coerceAtMost(maxZoom + zoomCorrection)
        else smoothedZoom

        val effectiveCompass = hasCompass && compassEnabled
        if (effectiveCompass) {
            val delta = ((azimuth - lastAzimuth + 540f) % 360f) - 180f
            onBearingUpdate((lastAzimuth + delta * kalmanGainBearing + 360f) % 360f)
        }
        val bearing = if (effectiveCompass) lastAzimuth else cameraState.position.bearing

        cameraState.position = CamPos(smoothedTarget, smoothedZoom - zoomCorrection, smoothedTilt, bearing)
    })

    // The frame loop. withFrameNanos suspends until the next display frame, so the
    // filter ticks at exactly the rate the screen refreshes — which is what it was
    // getting from recomposition before, minus the recomposition.
    //
    // Keyed on Unit: the loop must outlive every input change. Keying it on
    // anything else would cancel and restart the filter mid-motion.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            filter.tick()
        }
    }
}

// ── Generic scale bar ─────────────────────────────────────────────────────────

/**
 * A map scale bar computed from the camera zoom level and latitude using Mercator math.
 * Picks the largest "nice" distance (1/2/5 × 10^n) that fits within [width].
 */
@Composable
private fun GenericScaleBar(
    cameraState: MapLibreCameraState,
    modifier: Modifier = Modifier,
    width: Dp = 192.dp,
    barColor: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.secondary,
) {
    val zoom = cameraState.position.zoom
    val lat  = cameraState.position.target.latitude
    // Length the bar REPRESENTS, from its length in dp — because metersPerDp is
    // per logical pixel. This used to multiply by width.toPx(), i.e. by device
    // pixels, which overstated every distance on the bar by the display density:
    // measured at 2.43x on a 2.25-density phone, against four captures where the
    // separation the bar implied between the markers was compared with the Dist
    // the app printed in the same frame. The bar is the only on-screen check a
    // user has on how far away anything is, so it read as the map lying about
    // scale — which is exactly how it was reported.
    val totalMeters = metersPerDp(zoom, lat) * width.value

    val niceDistances = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500,
        1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000)
    val niceDistM = niceDistances.lastOrNull { it.toDouble() <= totalMeters } ?: niceDistances.first()
    val barFraction = (niceDistM.toDouble() / totalMeters).toFloat().coerceIn(0.05f, 1f)
    val label = if (niceDistM >= 1000) "${niceDistM / 1000} km" else "$niceDistM m"

    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
        Canvas(modifier = Modifier.width(width).height(8.dp)) {
            val barW  = size.width * barFraction
            val y     = size.height
            val stroke = 2.dp.toPx()
            drawLine(barColor, Offset(0f, y), Offset(barW, y), stroke)
            drawLine(barColor, Offset(0f, 0f), Offset(0f, y), stroke)
            drawLine(barColor, Offset(barW, 0f), Offset(barW, y), stroke)
        }
    }
}

// ── Map controls ──────────────────────────────────────────────────────────────

/**
 * Overlay column (shown on map tap) containing arm/disarm, auto-center,
 * and auto-zoom toggle buttons.
 */
@Composable
private fun MapControlsColumn(
    bluetoothConnectionState: BluetoothConnectionState,
    lastMessageAge: Long,
    // Aged separately because battery levels arrive ONLY in the pre-launch
    // message. Everything else on this panel is carried by both message types and
    // stays live through a flight; the batteries stop being refreshed the moment
    // the locator switches to telemetry.
    preLaunchDataAge: Long,
    receiverDeviceName: String,
    locatorConfig: LocatorConfig,
    rocketState: RocketState,
    armedState: Boolean,
    padAlert: PadAlertState,
    padAlertSnoozeMinutes: Int,
    locatorArmedMessageState: LocatorMessageState,
    onToggleArmed: () -> Unit,
    onRescan: () -> Unit,
    onSnoozePadAlert: () -> Unit,
    textToSpeech: TextToSpeech?,
    locatorConnected: Boolean = true,
    actionsExpanded: Boolean,
    onActionsExpandedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        val iconSize = 20.dp
        val iconBoxWidth = 40.dp   // wide enough for rocket icon + satellite superscript
        val nameWidth = 190.dp     // fits DEVICE_NAME_LENGTH characters at body size
        val batterySize = 20.dp
        val batteryBoxWidth = 24.dp
        // Total width of a status row; the action buttons match it so revealing the
        // dropdown never widens the panel beyond its collapsed size.
        val panelContentWidth = iconBoxWidth + nameWidth + batteryBoxWidth

        // ── Action panel expand/collapse ──────────────────────────────────────
        // The status rows are small on purpose, so instead of hunting for a fine
        // tap target the user taps anywhere on the panel to drop down large
        // "Rescan" and "Arm"/"Disarm" buttons.  It auto-collapses after a short
        // idle time, and the caller collapses it too when the map is tapped
        // (expanded state is hoisted).
        // Each snooze tap RESTARTS the collapse timer rather than defeating it.
        // Holding the panel open for as long as the alert sounded (the first
        // attempt) meant it never tidied itself away; collapsing on a fixed timer
        // meant every additive tap cost a re-open. Restarting on interaction gives
        // both: tap as often as you like, and it closes 5 s after you stop.
        var snoozeInteraction by remember { mutableIntStateOf(0) }
        LaunchedEffect(actionsExpanded, snoozeInteraction) {
            if (actionsExpanded) {
                delay(actionPanelCollapseDelay)
                onActionsExpandedChange(false)
            }
        }

        // ── Arm/disarm feedback state ─────────────────────────────────────────
        // While a command is in flight the rocket icon blinks toward its target
        // color (green when arming, white when disarming) until armedState
        // reflects the change or the 2 s timeout elapses.
        var armCommandPending by remember { mutableStateOf(false) }
        LaunchedEffect(armCommandPending) {
            if (armCommandPending) { delay(2000L); armCommandPending = false }
        }
        // Announce arm-state changes via TTS and clear the pending blink.
        val armedStateText = if (armedState)
            stringResource(R.string.armed_state_armed)
        else
            stringResource(R.string.armed_state_disarmed)
        LaunchedEffect(armedState) {
            textToSpeech?.speak(armedStateText, TextToSpeech.QUEUE_FLUSH, null, null)
            armCommandPending = false   // acknowledgment received
        }
        // Continuous blink animation — applied only while a command is pending.
        val blinkTransition = rememberInfiniteTransition(label = "rocketBlink")
        val blinkAlpha by blinkTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(450, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "rocketBlinkAlpha",
        )
        val rocketIconTint = when {
            armCommandPending -> if (!armedState) Color.Green else Color.White
            armedState        -> Color.Green
            else              -> Color.White
        }
        val rocketIconAlpha = if (armCommandPending) blinkAlpha else 1f
        // Arm/disarm is only accepted once the previous command has settled and
        // the locator is the connected one (password-verified, and the single
        // holder of the connection — an Arm must not reach a different rocket).
        val armActionEnabled = (locatorArmedMessageState == LocatorMessageState.Idle ||
            locatorArmedMessageState == LocatorMessageState.AckUpdated) && locatorConnected

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(mapOverlayBg)
                .padding(8.dp)
                .clickable { onActionsExpandedChange(!actionsExpanded) }
        ) {
            // Row 1: radio icon | receiver name | receiver battery
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(iconBoxWidth), contentAlignment = Alignment.CenterStart) {
                    Icon(
                        painter = painterResource(R.drawable.radio),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize)
                    )
                }
                Text(
                    text = when (bluetoothConnectionState) {
                        BluetoothConnectionState.Starting,
                        BluetoothConnectionState.Enabling -> "Enabling bluetooth"
                        BluetoothConnectionState.NotEnabled -> "BT not enabled"
                        BluetoothConnectionState.NotSupported -> "BT not supported"
                        BluetoothConnectionState.Enabled -> "Bluetooth enabled"
                        BluetoothConnectionState.AssociateStart -> "Scanning"
                        BluetoothConnectionState.LocationDisabled -> "Enable location"
                        BluetoothConnectionState.PairingFailed -> "Pairing failed"
                        BluetoothConnectionState.NoDevicesAvailable -> "Waiting for receiver"
                        BluetoothConnectionState.DevicesFound -> "Receivers found"
                        BluetoothConnectionState.Connected -> "Receiver connected"
                        BluetoothConnectionState.Ready -> receiverDeviceName
                        BluetoothConnectionState.Disconnected -> "Receiver disconnect"
                        else -> "Undefined state"
                    },
                    modifier = Modifier.width(nameWidth),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                val receiverBattery = rocketState.receiverBatteryLevel.coerceIn(0..7)
                Box(modifier = Modifier.width(batteryBoxWidth), contentAlignment = Alignment.CenterStart) {
                    // preLaunchDataAge, not lastMessageAge: the latter is refreshed
                    // by telemetry too, which carries no battery level, so gating on
                    // it left this icon lit for the whole flight showing whatever the
                    // charge was on the pad. Nothing beats a stale battery reading
                    // for being quietly wrong.
                    if (preLaunchDataAge < messageTimeout) {
                        Icon(
                            painter = painterResource(
                                context.resources.getIdentifier("battery_${receiverBattery}_bar", "drawable", context.packageName)
                            ),
                            contentDescription = stringResource(
                                context.resources.getIdentifier("battery_${receiverBattery}_bar", "string", context.packageName)
                            ),
                            modifier = Modifier.size(batterySize)
                        )
                    }
                }
            }

            // Row 2: rocket icon + satellite count | locator name | locator battery
            // Arm/disarm is handled by double-tapping anywhere on the status panel background.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(iconBoxWidth), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.rocket_md),
                            contentDescription = stringResource(R.string.locator_satellites),
                            tint = rocketIconTint,
                            modifier = Modifier
                                .size(iconSize)
                                .alpha(rocketIconAlpha)
                        )
                        if (lastMessageAge < messageTimeout) {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(
                                        style = SpanStyle(
                                            fontSize = 10.sp,
                                            baselineShift = BaselineShift.Superscript
                                        )
                                    ) { append(rocketState.satellites.toString()) }
                                }
                            )
                        }
                    }
                }
                Text(
                    text = if (lastMessageAge < messageTimeout) locatorConfig.deviceName else "",
                    modifier = Modifier.width(nameWidth),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                val locatorBattery = rocketState.locatorBatteryLevel.coerceIn(0..7)
                Box(modifier = Modifier.width(batteryBoxWidth), contentAlignment = Alignment.CenterStart) {
                    // Same as the receiver battery above — pre-launch only.
                    if (preLaunchDataAge < messageTimeout) {
                        Icon(
                            painter = painterResource(
                                context.resources.getIdentifier("battery_${locatorBattery}_bar", "drawable", context.packageName)
                            ),
                            contentDescription = stringResource(
                                context.resources.getIdentifier("battery_${locatorBattery}_bar", "string", context.packageName)
                            ),
                            modifier = Modifier.size(batterySize)
                        )
                    }
                }
            }
            // Row 3: signal icon | RSSI value
            if (lastMessageAge < messageTimeout) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(iconBoxWidth), contentAlignment = Alignment.CenterStart) {
                        Icon(
                            imageVector = Icons.Default.SignalCellularAlt,
                            contentDescription = "Signal strength",
                            modifier = Modifier.size(iconSize),
                            tint = rssiColor(rocketState.rssi)
                        )
                    }
                    Row(
                        modifier = Modifier.width(nameWidth),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${rocketState.rssi} dBm",
                            color = rssiColor(rocketState.rssi),
                            maxLines = 1,
                        )
                        Text(
                            text = "  ",
                            maxLines = 1,
                        )
                        Text(
                            text = "SNR ${rocketState.snr} dB",
                            color = snrColor(rocketState.snr),
                            maxLines = 1,
                        )
                    }
                }
            }
            // Interference verdict (ADR-0019). Sits under the RSSI readout rather
            // than in its own banner: it qualifies that number, and a second
            // competing alert on the map is exactly what gets ignored.
            //
            // Deliberately OUTSIDE the freshness gate above. When interference is
            // costing packets the locator goes stale, which hid this note at exactly
            // the moment it was worth reading — a red rocket and no explanation.
            // The verdict persists from the last accepted broadcast, which is the
            // most recent thing actually known about the channel.
            //
            // Silent on Normal — which includes a distant rocket at apogee, the case
            // an SNR-only rule would false-alarm on every flight, and a locator
            // simply switched off (its channel goes quiet, so no verdict is raised).
            // Width is pinned to the panel's own width — the widest row above — so
            // the note can never be what decides how wide the panel is.
            val noteWidth = iconBoxWidth + nameWidth + batteryBoxWidth
            when (rocketState.linkQuality) {
                LinkQuality.Verdict.Interference -> LinkQualityNote(
                    text = stringResource(R.string.link_interference),
                    color = MaterialTheme.colorScheme.error,
                    width = noteWidth,
                )
                LinkQuality.Verdict.Congested -> LinkQualityNote(
                    text = stringResource(R.string.link_congested),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    width = noteWidth,
                )
                LinkQuality.Verdict.Normal -> Unit
            }

            // ── Descending action buttons ─────────────────────────────────────
            // Large, clearly labeled touch targets revealed by tapping the panel.
            AnimatedVisibility(
                visible = actionsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .width(panelContentWidth)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onActionsExpandedChange(false)
                            onRescan()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text(stringResource(R.string.action_rescan))
                    }
                    // Snooze appears ONLY while the alert is actually sounding, so it
                    // cannot be pressed pre-emptively to keep a rocket permanently
                    // quiet. It is the operator saying "still prepping" (ADR-0021
                    // Decision 5) and the locator bounds it regardless of what the app
                    // asks for — a snooze that could be made indefinite is an off
                    // switch, and hands back the forgotten arm this exists to catch.
                    if (padAlert != PadAlertState.Quiet) {
                        // Stays available while snoozed so the operator can top up
                        // toward the ceiling, and does NOT collapse the panel on
                        // click — tapping to accumulate should not cost a re-open
                        // each time. Disabled at the ceiling rather than hidden, so
                        // "no more" is visible instead of the control vanishing.
                        val atCeiling =
                            padAlertSnoozeMinutes >= PadAlertState.SNOOZE_CEILING_MINUTES
                        Button(
                            onClick = {
                                onSnoozePadAlert()
                                snoozeInteraction++   // restart the collapse timer
                            },
                            enabled = !atCeiling,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Text(
                                if (padAlert == PadAlertState.Snoozed)
                                    stringResource(
                                        R.string.pad_alert_snooze_more,
                                        padAlertSnoozeMinutes,
                                        PadAlertState.SNOOZE_STEP_MINUTES,
                                    )
                                else
                                    stringResource(
                                        R.string.pad_alert_snooze_action,
                                        PadAlertState.SNOOZE_STEP_MINUTES,
                                    )
                            )
                        }
                    }
                    val disarming = armedState
                    Button(
                        onClick = {
                            onActionsExpandedChange(false)
                            // Mirror the locator rule: a disarm is only honored while
                            // the rocket is waiting for launch or has landed.  Block it
                            // in the app during flight so we don't send a request the
                            // locator would silently ignore, and say why with an
                            // auto-dismissing popup.
                            val inFlight = rocketState.flightState != FlightStates.WaitingLaunch &&
                                rocketState.flightState != FlightStates.Landed
                            if (disarming && inFlight) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.disarm_in_flight_blocked),
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                BluetoothManagerRepository.updateLocatorArmedMessageState(
                                    LocatorMessageState.SendRequested
                                )
                                onToggleArmed()
                                armCommandPending = true
                            }
                        },
                        enabled = armActionEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (disarming)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                            contentColor = if (disarming)
                                MaterialTheme.colorScheme.onError
                            else
                                MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text(
                            if (disarming) stringResource(R.string.action_disarm)
                            else stringResource(R.string.action_arm)
                        )
                    }
                }
            }
            } // end background Column
    }
}

// ── Locator stats overlay ─────────────────────────────────────────────────────

@Composable
fun LocatorStats(
    rocketState: RocketState,
    armedState: Boolean,
    distanceToLocator: Int,
    locatorConfig: LocatorConfig,
    viewModel: RocketViewModel,
    scaffoldSize: IntSize,
    textToSpeech: TextToSpeech?,
    modifier: Modifier
) {
    var columnWidth by remember { mutableStateOf(0) }
    var columnHeight by remember { mutableStateOf(0) }
    var locatorStatisticsOffset by remember { mutableStateOf(IntOffset(0, 0)) }
    // True when no saved position exists; resolved to lower-right once sizes are known.
    var needsDefaultPosition by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val saved = viewModel.locatorStatisticsOffset.value
        if (saved == IntOffset(0, 0)) needsDefaultPosition = true
        else locatorStatisticsOffset = saved
    }
    val density = LocalDensity.current
    // onSizeChanged sits inside padding(8.dp), so columnWidth/columnHeight are content-only.
    // Full visual panel size = content + 2 × panelPaddingPx on each axis.
    val marginPx = with(density) { 8.dp.roundToPx() }
    val panelPaddingPx = 2 * marginPx  // 8 dp padding on each side

    // Resolve the default position to lower-right as soon as both sizes are available.
    LaunchedEffect(needsDefaultPosition, scaffoldSize.width, scaffoldSize.height, columnWidth, columnHeight) {
        if (needsDefaultPosition && scaffoldSize.width > 0 && columnWidth > 0) {
            locatorStatisticsOffset = IntOffset(
                scaffoldSize.width - columnWidth - panelPaddingPx - marginPx,
                scaffoldSize.height - columnHeight - panelPaddingPx - marginPx,
            )
            needsDefaultPosition = false
        }
    }

    Column(
        modifier = modifier
            .offset { locatorStatisticsOffset }
            .clickable {
                // Same reasoning as the layout below: a disarmed rocket in flight
                // is exactly when someone wants to hear state and altitude without
                // looking, so this cannot be gated on arm state either (#36).
                if (isInFlight(armedState, rocketState))
                    textToSpeech?.speak(
                        "${locatorConfig.deviceName}, ${rocketState.flightState}, ${rocketState.altitudeAboveGroundLevel} meters",
                        TextToSpeech.QUEUE_FLUSH, null, null
                    )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // Floor the max bound at marginPx: while the scaffold or column is
                    // being (re)measured the available extent can go negative, and
                    // coerceIn(min, max) throws when max < min (crash on returning to
                    // the map screen — see onSizeChanged below).
                    locatorStatisticsOffset = IntOffset(
                        (locatorStatisticsOffset.x + dragAmount.x.toInt()).coerceIn(marginPx, maxOf(marginPx, scaffoldSize.width - columnWidth - panelPaddingPx - marginPx)),
                        (locatorStatisticsOffset.y + dragAmount.y.toInt()).coerceIn(marginPx, maxOf(marginPx, scaffoldSize.height - columnHeight - panelPaddingPx - marginPx))
                    )
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .background(mapOverlayBg)
            .padding(8.dp)
            .onSizeChanged { size ->
                columnWidth = size.width
                columnHeight = size.height
                if (!needsDefaultPosition) {
                    // Floor the max bound at marginPx so a not-yet-measured scaffold
                    // (width == 0 on return to the map screen) can't produce max < min,
                    // which makes coerceIn throw "Cannot coerce value to an empty range".
                    locatorStatisticsOffset = IntOffset(
                        locatorStatisticsOffset.x.coerceIn(marginPx, maxOf(marginPx, scaffoldSize.width - columnWidth - panelPaddingPx - marginPx)),
                        locatorStatisticsOffset.y.coerceIn(marginPx, maxOf(marginPx, scaffoldSize.height - columnHeight - panelPaddingPx - marginPx))
                    )
                }
            }
            .defaultMinSize(minWidth = 160.dp),
    ) {
        // Layout follows whether the rocket is FLYING, not whether it is armed.
        // Those were the same thing until #36 let a disarmed locator fly, and the
        // panel still conflated them: a disarmed flight got the on-pad layout and
        // never showed flight state at all. Worse, the pad layout's accel/gyro
        // rows come from PreLaunchData, which stops arriving once the locator
        // switches to telemetry — so they froze at their last pad values, which
        // is what "the stats area didn't change" looked like.
        //
        // Armed-and-waiting still gets the flight layout, exactly as before.
        val inFlight = isInFlight(armedState, rocketState)

        // ── Telemetry rows ────────────────────────────────────────────────────
        // "Unknown" beats a number that cannot be true. The figure is suppressed
        // for a coordinate that is no fix at all (0,0 before the locator has one,
        // or non-finite) and for one the locator cannot be at — 779 km away while
        // reporting no satellites, from a radio we are receiving. A stale but
        // believable distance is still shown: it is what the user walks toward.
        val distancePlausible = viewModel.locatorDistancePlausible.collectAsState().value
        val dst = if (validLatLng(rocketState.latitude, rocketState.longitude) && distancePlausible)
            String.format(Locale.US, "%15d", distanceToLocator) + " m"
        else stringResource(R.string.unknown)
        Text(
            text = "Dist: $dst",
            color = if (rocketState.gpsStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            style = TelemetryTextStyle,
        )
        Text(
            text = "AGL : ${String.format(Locale.US, "%15.1f", rocketState.altitudeAboveGroundLevel)} m",
            style = TelemetryTextStyle,
            color = if (rocketState.baroStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
        )
        if (inFlight) {
            Text(
                text = "Spd: ${String.format(Locale.US, "%6.1f", rocketState.velocity)} m/s",
                style = TelemetryTextStyle,
            )
            val inc = rocketState.attitude.inclinationDeg()
            val hdg = rocketState.attitude.headingDeg()
            Text(
                text = "Inc:${String.format(Locale.US, "%5.1f", inc)}° Hdg:${String.format(Locale.US, "%5.1f", hdg)}°",
                style = TelemetryTextStyle,
                color = if (rocketState.imuStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = "Accl: ${String.format(Locale.US, "%5.1f", rocketState.accelerometer.x / G_FORCE_MS2)}" +
                        " ${String.format(Locale.US, "%5.1f", rocketState.accelerometer.y / G_FORCE_MS2)}" +
                        " ${String.format(Locale.US, "%5.1f", rocketState.accelerometer.z / G_FORCE_MS2)}",
                style = TelemetryTextStyle,
                color = if (rocketState.imuStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Gyro: ${String.format(Locale.US, "%5.0f", rocketState.gyro.x * RAD2DEG)}" +
                        " ${String.format(Locale.US, "%5.0f", rocketState.gyro.y * RAD2DEG)}" +
                        " ${String.format(Locale.US, "%5.0f", rocketState.gyro.z * RAD2DEG)}",
                style = TelemetryTextStyle,
                color = if (rocketState.imuStatus == SensorHealth.Ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }

        // ── Flight state (in flight) or deployment channel config (on pad) ────
        if (inFlight) {
            Text(
                text = when (rocketState.flightState) {
                    FlightStates.WaitingLaunch -> "Waiting For Launch"
                    FlightStates.Launched -> "Launched"
                    FlightStates.Burnout -> "Burnout"
                    FlightStates.Noseover -> "Noseover"
                    FlightStates.DroguePrimaryEvent -> "Drogue Primary"
                    FlightStates.DrogueBackupEvent -> "Drogue Backup"
                    FlightStates.MainPrimaryEvent -> "Main Primary"
                    FlightStates.MainBackupEvent -> "Main Backup"
                    FlightStates.Landed -> "Landed"
                    else -> ""
                },
                style = TelemetryTextStyle,
            )
        } else {
            listOf(
                ChannelConfig(locatorConfig.deploymentChannel1Mode, rocketState.deployChannel1Armed),
                ChannelConfig(locatorConfig.deploymentChannel2Mode, rocketState.deployChannel2Armed),
                ChannelConfig(locatorConfig.deploymentChannel3Mode, rocketState.deployChannel3Armed),
                ChannelConfig(locatorConfig.deploymentChannel4Mode, rocketState.deployChannel4Armed),
            ).forEachIndexed { index, (mode, isArmed) ->
                Text(
                    text = deployChannelText(index + 1, mode, locatorConfig),
                    style = TelemetryTextStyle,
                    color = if (isArmed) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
            }
        }

        // Tapping the coordinates hands them to a mapping app. The geo: scheme is
        // resolved by the OS, so the user's own choice of app wins — which matters
        // here more than usual: recovery happens where there is no cell signal
        // (ADR-0014), and only an app with offline data downloaded will show
        // anything useful when it opens.
        //
        // Offered only for a position the app is willing to stand behind.
        // ADR-0022 already refuses to quote a distance it cannot justify, and
        // handing that same position to a navigation app would walk straight past
        // that judgment — literally.
        if (rocketState.rawLatitude != 0.0 && rocketState.rawLongitude != 0.0) {
            val context = LocalContext.current
            // BigDecimal.toString() is locale-independent. String.format without
            // Locale.US would write a comma decimal separator in a de-DE locale,
            // which silently corrupts the URI as well as the display.
            val lat = BigDecimal(rocketState.rawLatitude).setScale(6, RoundingMode.HALF_UP)
            val lon = BigDecimal(rocketState.rawLongitude).setScale(6, RoundingMode.HALF_UP)
            // Probed with a fixed URI rather than the live one: resolution does not
            // depend on the coordinates, and re-resolving against a URI that
            // changes at 1 Hz would put a PackageManager IPC on every fix. Needs
            // the <queries> geo: entry in the manifest to see anything on API 30+.
            val mapsAppInstalled = remember {
                context.packageManager.resolveActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=0,0")), 0
                ) != null
            }
            val navigable = mapsAppInstalled &&
                validLatLng(rocketState.rawLatitude, rocketState.rawLongitude) &&
                distancePlausible
            // Resolved out here: stringResource is composable and the tap lambda
            // is not. q= drops a labeled pin; a bare geo:lat,lon only centers the
            // camera, which is the less useful of the two when the point is to
            // walk to it.
            val pinLabel = locatorConfig.deviceName.ifBlank { stringResource(R.string.rocket_pin_label) }
            Text(
                modifier = if (navigable) modifier.clickable {
                    val geoUri = Uri.parse("geo:0,0?q=$lat,$lon(${Uri.encode(pinLabel)})")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                    } catch (_: ActivityNotFoundException) {
                        // resolveActivity above can go stale — the maps app may be
                        // uninstalled while the panel is on screen — and an
                        // uncaught ActivityNotFoundException takes the app down.
                        Toast.makeText(context, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
                    }
                } else modifier,
                text = "$lat,$lon",
                style = TelemetryTextStyle,
                // The only affordance the row can carry at this size. Absent when
                // the tap is not offered, so it never invites a press that does
                // nothing.
                textDecoration = if (navigable) TextDecoration.Underline else null,
            )
        }
    }

    LaunchedEffect(locatorStatisticsOffset) {
        viewModel.updateLocatorStatisticsOffset(locatorStatisticsOffset)
        viewModel.saveUserPreferences()
    }
}

// ── CameraPreview overlay helpers ────────────────────────────────────────────

private fun DrawScope.drawVelocityGauge(
    speed: Float,
    maxSpeed: Float,
    cx: Float,
    cy: Float,
    radius: Float,
    gaugeColor: Color,
    gaugeBgColor: Color,
    labelPaint: android.graphics.Paint,
    labelPx: Float,
    stroke: Float,
) {
    // Semicircle arc: starts at 210° (lower-left) sweeps 120° clockwise to 330° (lower-right)
    // 0 m/s at 210°, maxSpeed at 330°
    val startDeg = 210f
    val sweepDeg = 120f

    // Background arc
    drawArc(
        color = gaugeBgColor,
        startAngle = startDeg,
        sweepAngle = sweepDeg,
        useCenter = true,
        topLeft = Offset(cx - radius, cy - radius),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
    )

    // Tick marks at 0, 100, 200, 300, 400, 500 m/s
    val tickSpeeds = listOf(0f, 100f, 200f, 300f, 400f, 500f)
    for (tickSpeed in tickSpeeds) {
        val frac = (tickSpeed / maxSpeed).coerceIn(0f, 1f)
        val angleDeg = startDeg + frac * sweepDeg
        val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val innerR = radius * 0.75f
        val outerR = radius * 0.95f
        drawLine(
            color = gaugeColor,
            start = Offset(cx + cos(angleRad) * innerR, cy + sin(angleRad) * innerR),
            end   = Offset(cx + cos(angleRad) * outerR, cy + sin(angleRad) * outerR),
            strokeWidth = stroke * 1.5f,
        )
        val labelR = radius * 0.62f
        drawContext.canvas.nativeCanvas.drawText(
            "${tickSpeed.toInt()}",
            cx + cos(angleRad) * labelR,
            cy + sin(angleRad) * labelR + labelPx / 3f,
            labelPaint,
        )
    }

    // Colored arc showing current speed
    val speedFrac = (speed / maxSpeed).coerceIn(0f, 1f)
    val speedColor = when {
        speedFrac < 0.5f -> gaugeColor
        speedFrac < 0.8f -> Color(0xFFFF9800)
        else             -> Color(0xFFF44336)
    }
    if (speedFrac > 0f) {
        drawArc(
            color = speedColor,
            startAngle = startDeg,
            sweepAngle = speedFrac * sweepDeg,
            useCenter = false,
            topLeft = Offset(cx - radius * 0.88f, cy - radius * 0.88f),
            size = androidx.compose.ui.geometry.Size(radius * 1.76f, radius * 1.76f),
            style = Stroke(width = stroke * 3f),
        )
    }

    // Needle
    val needleAngleDeg = startDeg + speedFrac * sweepDeg
    val needleRad = Math.toRadians(needleAngleDeg.toDouble()).toFloat()
    drawLine(
        color = Color.White,
        start = Offset(cx, cy),
        end   = Offset(cx + cos(needleRad) * radius * 0.7f, cy + sin(needleRad) * radius * 0.7f),
        strokeWidth = stroke * 1.5f,
    )
    drawCircle(Color.White, radius = stroke * 2.5f, center = Offset(cx, cy))

    // Speed text in center
    val speedPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = labelPx * 1.4f
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(
        "${speed.toInt()} m/s", cx, cy + radius * 0.35f, speedPaint)
}

private fun DrawScope.drawRocket3D(
    attitude: Quaternionf,
    cx: Float,
    cy: Float,
    scale: Float,
    gaugeBgColor: Color,
    stroke: Float,
) {
    // Background circle
    drawCircle(gaugeBgColor, radius = scale * 1.1f, center = Offset(cx, cy))

    // Rotation matrix from q_bn (body-to-NED)
    val w = attitude.w; val qx = attitude.x; val qy = attitude.y; val qz = attitude.z
    // R columns: each column i is where body axis i lands in NED
    val r00 = 1f - 2f*(qy*qy + qz*qz);  val r01 = 2f*(qx*qy - w*qz);  val r02 = 2f*(qx*qz + w*qy)
    val r10 = 2f*(qx*qy + w*qz);         val r11 = 1f - 2f*(qx*qx + qz*qz); val r12 = 2f*(qy*qz - w*qx)
    val r20 = 2f*(qx*qz - w*qy);         val r21 = 2f*(qy*qz + w*qx);  val r22 = 1f - 2f*(qx*qx + qy*qy)

    // Project NED point (pN, pE, pD) to screen using isometric view
    // Screen X = (pE - pN) * cos30°, Screen Y = (pN + pE) * sin30° + pD
    // (East→right, North→upper-left, Down→down)
    val cos30 = cos(PI.toFloat() / 6f)
    val sin30 = sin(PI.toFloat() / 6f)
    fun project(bx: Float, by: Float, bz: Float): Offset {
        val pN = r00*bx + r01*by + r02*bz
        val pE = r10*bx + r11*by + r12*bz
        val pD = r20*bx + r21*by + r22*bz
        val sx = (pE - pN) * cos30
        val sy = (pN + pE) * sin30 + pD
        return Offset(cx + sx * scale, cy + sy * scale)
    }

    // Rocket geometry in body frame (x = nose axis)
    val nRing = 6
    val bodyR = 0.08f
    val noseX = 1.0f; val noseBaseX = 0.65f
    val tailX = -0.5f
    val finLen = 0.28f; val finChordX = -0.85f

    // Compute ring points
    fun ring(xPos: Float, radius: Float) = Array(nRing) { i ->
        val a = 2f * PI.toFloat() * i / nRing
        floatArrayOf(xPos, radius * cos(a), radius * sin(a))
    }
    val noseRing = ring(noseBaseX, bodyR)
    val tailRing = ring(tailX, bodyR)

    // Depth-sort: compute average NED depth (pD) per segment, draw back-to-front
    data class Seg(val p1: Offset, val p2: Offset, val color: Color, val width: Float, val depth: Float)
    val segs = mutableListOf<Seg>()

    fun addSeg(bx1: Float, by1: Float, bz1: Float, bx2: Float, by2: Float, bz2: Float,
               color: Color, width: Float) {
        val pD1 = r20*bx1 + r21*by1 + r22*bz1
        val pD2 = r20*bx2 + r21*by2 + r22*bz2
        segs.add(Seg(project(bx1, by1, bz1), project(bx2, by2, bz2), color, width, (pD1+pD2)*0.5f))
    }

    val bodyColor = Color(0xFFB0C8E8)
    val noseColor = Color(0xFFFF6060)
    val finColor  = Color(0xFF80A0C0)

    // Nose cone lines (noseRing → tip)
    val noseTip = floatArrayOf(noseX, 0f, 0f)
    for (i in 0 until nRing)
        addSeg(noseRing[i][0], noseRing[i][1], noseRing[i][2],
               noseTip[0], noseTip[1], noseTip[2], noseColor, stroke * 1.5f)

    // Nose ring
    for (i in 0 until nRing) {
        val j = (i + 1) % nRing
        addSeg(noseRing[i][0], noseRing[i][1], noseRing[i][2],
               noseRing[j][0], noseRing[j][1], noseRing[j][2], bodyColor, stroke)
    }

    // Longitudinal body lines
    for (i in 0 until nRing)
        addSeg(noseRing[i][0], noseRing[i][1], noseRing[i][2],
               tailRing[i][0], tailRing[i][1], tailRing[i][2], bodyColor, stroke)

    // Tail ring
    for (i in 0 until nRing) {
        val j = (i + 1) % nRing
        addSeg(tailRing[i][0], tailRing[i][1], tailRing[i][2],
               tailRing[j][0], tailRing[j][1], tailRing[j][2], bodyColor, stroke)
    }

    // 4 fins: ±y and ±z directions
    for ((fy, fz) in listOf(Pair(1f, 0f), Pair(-1f, 0f), Pair(0f, 1f), Pair(0f, -1f))) {
        val tipY = fy * (bodyR + finLen); val tipZ = fz * (bodyR + finLen)
        addSeg(tailX, fy*bodyR, fz*bodyR, finChordX, fy*bodyR, fz*bodyR, finColor, stroke * 1.2f)
        addSeg(finChordX, fy*bodyR, fz*bodyR, tailX, tipY, tipZ,          finColor, stroke * 1.2f)
        addSeg(tailX, tipY, tipZ, tailX, fy*bodyR, fz*bodyR,              finColor, stroke * 1.2f)
    }

    // Draw back-to-front (painter's algorithm)
    segs.sortByDescending { it.depth }
    for (seg in segs) {
        drawLine(color = seg.color, start = seg.p1, end = seg.p2, strokeWidth = seg.width,
            cap = StrokeCap.Round)
    }
}

// ── Link quality note (ADR-0019) ──────────────────────────────────────────────

/** One-line qualifier under the RSSI readout, aligned to the same icon gutter. */
@Composable
private fun LinkQualityNote(text: String, color: Color, width: Dp) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        // FIXED width, matching the rows above, so a long message wraps instead of
        // widening the panel. Unconstrained it laid out on one line and dragged the
        // whole status block wider whenever the verdict changed.
        //
        // The width spans the icon gutter too. The note is prose, not a column
        // entry, so it has no reason to align with the readouts above and every
        // reason to use the ~40 dp the icons leave empty — which is what keeps
        // these messages to one line at default font scale.
        //
        // Height is deliberately left free: wrapping to a second line at larger
        // font scales is fine, and reserving a fixed number of lines would either
        // waste one permanently or clip the message.
        modifier = Modifier.width(width).padding(end = 4.dp),
    )
}

// ── RSSI signal strength color ────────────────────────────────────────────────

private fun rssiColor(rssi: Int): Color = when {
    rssi >= -80  -> Color(0xFF4CAF50)  // green  — excellent
    rssi >= -100 -> Color(0xFFFFC107)  // amber  — good
    rssi >= -110 -> Color(0xFFFF9800)  // orange — fair
    else         -> Color(0xFFF44336)  // red    — poor
}

// ── SNR margin color ──────────────────────────────────────────────────────────

// How much room is left above the SF7 demodulator floor (about -7.5 dB) — i.e.
// how close the link is to dropping packets, whatever the cause.
//
// Deliberately NOT the interference rule. Low SNR at range is normal and expected
// near apogee, so this reads as "margin is thinning", not "something is wrong".
// Whether the cause is distance or another emitter is the separate, quieter
// verdict under this row, which stays silent unless the signal is *also* strong
// (ADR-0019). Coloring SNR by the interference rule would put the apogee false
// alarm back in, as color instead of text.
private fun snrColor(snr: Int): Color = when {
    snr >= 5  -> Color(0xFF4CAF50)  // green  — wide margin
    snr >= 0  -> Color(0xFFFFC107)  // amber  — comfortable
    snr >= -5 -> Color(0xFFFF9800)  // orange — thinning
    else      -> Color(0xFFF44336)  // red    — near the demod floor
}

// ── Camera preview (landscape mode) ──────────────────────────────────────────

@Composable
fun CameraPreviewScreen(
    handheldDeviceAzimuth: Float,
    locatorAzimuth: Float,
    handheldDevicePitch: Float,
    locatorElevation: Float,
    rocketSpeed: Float = 0f,
    rocketAttitude: Quaternionf = Quaternionf.IDENTITY,
    // Whether the rocket is FLYING, not whether it is armed — this screen has no
    // RocketState to decide for itself, so the caller passes the verdict (#36).
    inFlight: Boolean = false,
    lastMessageAge: Long = Long.MAX_VALUE,
    // False when the position the bearing was computed from failed the distance
    // plausibility test. The locator marker and both gauge pointers are drawn
    // from it, and an AR overlay is more assertive than a readout: it puts a
    // circle on a patch of sky and invites the user to walk that way. Suppressed
    // rather than reworded, and the crosshair and gauge scales stay — they are
    // the reference frame, not a claim about where the rocket is.
    bearingValid: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context)
        provider.addListener({ cameraProvider = provider.get() }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    if (cameraProvider != null) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                    camera = cameraProvider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                }
            }
        )

        // Angle deltas: positive horizontalDelta = locator is to the right of camera aim;
        // positive verticalDelta = locator is below camera aim (canvas Y grows downward).
        val horizontalDelta = ((locatorAzimuth   - handheldDeviceAzimuth + 540f) % 360f) - 180f
        val verticalDelta   = ((handheldDevicePitch - locatorElevation   + 540f) % 360f) - 180f

        val density   = LocalDensity.current
        val config    = LocalConfiguration.current
        val scrW = with(density) { config.screenWidthDp.dp.toPx() }
        val scrH = with(density) { config.screenHeightDp.dp.toPx() }

        // Colors
        val locatorColor   = Color(0xFFFF6080)   // red-pink for locator marker
        val crosshairColor = Color(0xFFC0FFC0)   // soft green for crosshair
        val gaugeColor     = Color(0xFFFFC040)   // amber for HUD gauges
        val gaugeBgColor   = Color(0x80000000)   // 50 % black gauge background

        // Pre-build native text paints for gauge labels (must be outside DrawScope).
        val labelPx = with(density) { 10.sp.toPx() }
        val hLabelPaint = remember { android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 255, 192, 64)
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        } }.also { it.textSize = labelPx }
        val vLabelPaint = remember { android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 255, 192, 64)
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        } }.also { it.textSize = labelPx }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        zoomRatio = if (zoomRatio == 1f)
                            camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                        else 1f
                    }
                }
        ) {
            val stroke = 2.dp.toPx()
            val cx = scrW / 2f
            val cy = scrH / 2f

            // ── Crosshair ─────────────────────────────────────────────────────
            val gap = 50.dp.toPx()
            val arm = 25.dp.toPx()
            drawLine(crosshairColor, Offset(cx, cy - gap - arm), Offset(cx, cy - gap), stroke)
            drawLine(crosshairColor, Offset(cx + gap + arm, cy), Offset(cx + gap, cy), stroke)
            drawLine(crosshairColor, Offset(cx, cy + gap + arm), Offset(cx, cy + gap), stroke)
            drawLine(crosshairColor, Offset(cx - gap - arm, cy), Offset(cx - gap, cy), stroke)

            // ── Locator circle (on-screen) or edge arrow (off-screen) ─────────
            val scale  = 10f
            val radius = 50.dp.toPx()
            val lx = cx + horizontalDelta * scale
            val ly = cy + verticalDelta   * scale

            if (!bearingValid) {
                // Nothing drawn: no marker, and no edge arrow either. An arrow is
                // the more confident of the two — it says the rocket is off-screen
                // in this direction, which is exactly the claim we cannot make.
            } else if (lx in -radius..(scrW + radius) && ly in -radius..(scrH + radius)) {
                drawCircle(locatorColor, radius, Offset(lx, ly), style = Stroke(stroke))
            } else {
                // Clamp circle center to screen edge with a small margin, then
                // draw a triangle arrow pointing from the edge toward the locator.
                val em = 20.dp.toPx()
                val ex = lx.coerceIn(em, scrW - em)
                val ey = ly.coerceIn(em, scrH - em)
                val dx = lx - ex;  val dy = ly - ey
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val nx = dx / len; val ny = dy / len   // unit vector toward locator
                val px = -ny;      val py = nx          // perpendicular
                val arrowSz = 14.dp.toPx()
                val arrowPath = Path().apply {
                    moveTo(ex + nx * arrowSz,              ey + ny * arrowSz)
                    lineTo(ex + px * arrowSz * 0.5f,       ey + py * arrowSz * 0.5f)
                    lineTo(ex - px * arrowSz * 0.5f,       ey - py * arrowSz * 0.5f)
                    close()
                }
                drawPath(arrowPath, locatorColor)
            }

            // ── Horizontal HUD gauge (bottom, shows left/right delta) ─────────
            val gaugeRange  = 45f      // degrees shown each side of center
            val tickMinor   = 5f       // minor tick every 5°
            val tickMajorMod = 15      // major tick every 15°

            val hGaugeW  = scrW * 0.65f
            val hGaugeH  = 22.dp.toPx()
            val hLeft    = (scrW - hGaugeW) / 2f
            val hBottom  = scrH - 16.dp.toPx()
            val hTop     = hBottom - hGaugeH
            val hMidY    = (hTop + hBottom) / 2f
            val hPpd     = hGaugeW / (2f * gaugeRange)   // pixels per degree

            drawRect(gaugeBgColor, Offset(hLeft, hTop),
                androidx.compose.ui.geometry.Size(hGaugeW, hGaugeH))

            // Tick marks
            var d = -gaugeRange
            while (d <= gaugeRange + 0.01f) {
                val tx = cx + d * hPpd
                if (tx in hLeft..(hLeft + hGaugeW)) {
                    val major = d.toInt() % tickMajorMod == 0
                    val th = if (major) hGaugeH * 0.7f else hGaugeH * 0.35f
                    drawLine(if (major) gaugeColor else gaugeColor.copy(alpha = 0.45f),
                        Offset(tx, hMidY - th / 2f), Offset(tx, hMidY + th / 2f),
                        if (major) stroke else stroke * 0.5f)
                }
                d += tickMinor
            }
            // Center reference (zero mark)
            drawLine(Color.White, Offset(cx, hTop), Offset(cx, hBottom), stroke * 1.5f)

            // Indicator triangle above the bar, tip pointing down into it
            if (bearingValid) {
                val hiX    = (cx + horizontalDelta * hPpd).coerceIn(hLeft, hLeft + hGaugeW)
                val hTriH  = hGaugeH * 0.8f
                val hTriPath = Path().apply {
                    moveTo(hiX,                  hTop - 1.dp.toPx())
                    lineTo(hiX - hTriH * 0.5f,  hTop - hTriH)
                    lineTo(hiX + hTriH * 0.5f,  hTop - hTriH)
                    close()
                }
                drawPath(hTriPath, locatorColor)
            }

            // Degree labels at ±gaugeRange, ±gaugeRange/2, 0
            listOf(-gaugeRange, -gaugeRange / 2f, 0f, gaugeRange / 2f, gaugeRange).forEach { ld ->
                val lx2 = cx + ld * hPpd
                if (lx2 in hLeft..(hLeft + hGaugeW))
                    drawContext.canvas.nativeCanvas.drawText(
                        "${ld.toInt()}°", lx2, hBottom + labelPx + 2.dp.toPx(), hLabelPaint)
            }

            // ── Vertical HUD gauge (right edge, shows up/down delta) ──────────
            val vGaugeH  = scrH * 0.55f
            val vGaugeW  = 22.dp.toPx()
            val vRight   = scrW - 16.dp.toPx()
            val vLeft    = vRight - vGaugeW
            val vTop     = cy - vGaugeH / 2f
            val vBottom  = cy + vGaugeH / 2f
            val vMidX    = (vLeft + vRight) / 2f
            val vPpd     = vGaugeH / (2f * gaugeRange)

            drawRect(gaugeBgColor, Offset(vLeft, vTop),
                androidx.compose.ui.geometry.Size(vGaugeW, vGaugeH))

            var vd = -gaugeRange
            while (vd <= gaugeRange + 0.01f) {
                val ty = cy + vd * vPpd
                if (ty in vTop..vBottom) {
                    val major = vd.toInt() % tickMajorMod == 0
                    val tw = if (major) vGaugeW * 0.7f else vGaugeW * 0.35f
                    drawLine(if (major) gaugeColor else gaugeColor.copy(alpha = 0.45f),
                        Offset(vMidX - tw / 2f, ty), Offset(vMidX + tw / 2f, ty),
                        if (major) stroke else stroke * 0.5f)
                }
                vd += tickMinor
            }
            drawLine(Color.White, Offset(vLeft, cy), Offset(vRight, cy), stroke * 1.5f)

            // Indicator triangle left of the bar, tip pointing right into it
            if (bearingValid) {
                val viY    = (cy + verticalDelta * vPpd).coerceIn(vTop, vBottom)
                val vTriW  = vGaugeW * 0.8f
                val vTriPath = Path().apply {
                    moveTo(vLeft - 1.dp.toPx(),   viY)
                    lineTo(vLeft - vTriW,          viY - vTriW * 0.5f)
                    lineTo(vLeft - vTriW,          viY + vTriW * 0.5f)
                    close()
                }
                drawPath(vTriPath, locatorColor)
            }

            // Degree labels
            listOf(-gaugeRange, -gaugeRange / 2f, 0f, gaugeRange / 2f, gaugeRange).forEach { ld ->
                val ly2 = cy + ld * vPpd
                if (ly2 in vTop..vBottom)
                    drawContext.canvas.nativeCanvas.drawText(
                        "${ld.toInt()}°", vLeft - 4.dp.toPx(), ly2 + labelPx / 3f, vLabelPaint)
            }

            if (inFlight && lastMessageAge < messageTimeout) {
                // ── Velocity arc gauge (top-left) ─────────────────────────────
                drawVelocityGauge(
                    speed = rocketSpeed,
                    maxSpeed = 500f,
                    cx = 100.dp.toPx(),
                    cy = 100.dp.toPx(),
                    radius = 80.dp.toPx(),
                    gaugeColor = gaugeColor,
                    gaugeBgColor = gaugeBgColor,
                    labelPaint = hLabelPaint,
                    labelPx = labelPx,
                    stroke = stroke,
                )

                // ── 3D rocket attitude (top-right) ────────────────────────────
                drawRocket3D(
                    attitude = rocketAttitude,
                    cx = scrW - 100.dp.toPx(),
                    cy = 100.dp.toPx(),
                    scale = 70.dp.toPx(),
                    gaugeBgColor = gaugeBgColor,
                    stroke = stroke,
                )
            }
        }
    }

    LaunchedEffect(zoomRatio) {
        camera?.cameraControl?.setZoomRatio(zoomRatio)
    }
}

// ── Text animation composables ────────────────────────────────────────────────

/**
 * Center-screen banner that pulses only when [pulse] is set.
 *
 * [pulse] has NO DEFAULT on purpose. A pulse is a claim on the user's attention
 * and it is expensive to make: an infinite alpha transition redraws the screen on
 * every display frame for as long as it is visible, which on this app's main
 * screen means holding the panel at its maximum refresh rate for the whole
 * pre-flight wait — measured as 1220 rendered frames per 10 s on the map against
 * 0 on a screen with no such animation. A default of `true` would let the next
 * call site buy that silently. Every caller states its intent.
 *
 * The transition is not merely left unread when [pulse] is false — it is not
 * created at all. An animation nobody reads still keeps the frame clock
 * subscribed, so skipping the read would have saved nothing.
 */
@Composable
fun PulsingText(
    modifier: Modifier = Modifier,
    text: String,
    pulse: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    minAlpha: Float = 0f,
    maxAlpha: Float = 1f,
    durationMillis: Int = 500,
) {
    val textModifier = if (pulse) {
        val transition = rememberInfiniteTransition(label = "PulseTransition")
        val alpha = transition.animateFloat(
            initialValue = minAlpha,
            targetValue = maxAlpha,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "AlphaPulse"
        ).value
        modifier.alpha(alpha)
    } else modifier   // no alpha layer either — a constant 1f still costs one
    Text(text = text, color = color, textAlign = textAlign, style = style,
        modifier = textModifier)
}

@Composable
fun BlinkingText(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    intervalMillis: Long = 500,
) {
    var visible by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "BlinkAlpha"
    )
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMillis)
            visible = !visible
        }
    }
    Text(text = text, color = color, textAlign = textAlign, style = style,
        modifier = Modifier.alpha(alpha))
}

// ── Exit button ───────────────────────────────────────────────────────────────

@Composable
fun ExitAppButton(activity: Activity) {
    var showDialog by remember { mutableStateOf(false) }
    Button(onClick = { showDialog = true }) { Text("Exit App") }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Exit App") },
            text = { Text("Are you sure you want to exit the app?") },
            confirmButton = {
                Button(onClick = { showDialog = false; activity.finish() }) { Text("Yes") }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) { Text("No") }
            }
        )
    }
}
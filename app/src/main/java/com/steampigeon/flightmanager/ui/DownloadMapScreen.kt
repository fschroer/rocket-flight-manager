package com.steampigeon.flightmanager.ui

// ---------------------------------------------------------------------------
// Offline map download screen. The user pans/zooms the satellite map to frame a
// launch area, picks how deep to cache (max zoom), sees a live tile-count / storage
// estimate, and downloads. Downloaded regions then render in the live FlightMapScreen
// map with no connectivity. Also lists/deletes existing regions.
// ---------------------------------------------------------------------------

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

/** Default box size when jumping to a manually-entered coordinate. */
private const val MANUAL_EXTENT_KM = 8.0

/** Semi-transparent backing for map-overlay text, so it stays legible over any terrain. */
private val mapHintScrim = Color(0xC05D6F96)

/** A request to re-frame the picker camera. [id] makes repeat selections re-fire. */
private data class MoveRequest(val bounds: LatLngBounds, val id: Long)

@Composable
fun DownloadMapScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var provider by remember { mutableStateOf(MapProviderPrefs.get(context)) }
    val manager = remember(provider) { OfflineMapManager(context, provider) }
    val styleJson = remember(provider) { provider.styleJson(context) }

    var bounds by remember { mutableStateOf<LatLngBounds?>(null) }
    var maxZoom by remember { mutableStateOf(17) }
    // Always cache down to the provider's floor, not maxZoom-N: each lower level has ~4x
    // fewer tiles, so the whole context pyramid costs almost nothing — while omitting it
    // would leave the map blank offline at any normal zoomed-out level (e.g. a z18–22
    // region shows nothing at z12–17, exactly when you're getting your bearings on-site).
    val minZoom = remember(maxZoom) { provider.minOfflineZoom.coerceAtMost(maxZoom) }
    var siteName by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<OfflineMapManager.Progress?>(null) }
    var regions by remember { mutableStateOf<List<OfflineMapManager.RegionInfo>>(emptyList()) }

    // Preset sites (user-editable CSV) + manual coordinate entry. Both drive the picker
    // camera via a MoveRequest; the id makes re-selecting the same site move again.
    val presets = remember { LaunchSiteRepository.load(context) }
    var presetsExpanded by remember { mutableStateOf(false) }
    var moveRequest by remember { mutableStateOf<MoveRequest?>(null) }
    var latLonText by remember { mutableStateOf("") }
    var latLonError by remember { mutableStateOf(false) }

    fun refreshRegions() = manager.listRegions { regions = it }
    DisposableEffect(Unit) { refreshRegions(); onDispose { } }

    val tiles = remember(bounds, minZoom, maxZoom) {
        bounds?.let { OfflineMapManager.tileCount(it, minZoom, maxZoom) } ?: 0L
    }
    val groundSize = remember(bounds) { bounds?.groundSizeKm() }
    val estBytes = remember(bounds, minZoom, maxZoom, provider) {
        bounds?.let { OfflineMapManager.estimateBytes(it, minZoom, maxZoom, provider) } ?: 0L
    }
    val downloading = progress is OfflineMapManager.Progress.Downloading

    Column(modifier = modifier.fillMaxSize()) {
        // Region picker map — frames the area to download.
        // Square on purpose. The download takes the VISIBLE bounds, so the preview's shape
        // becomes the region's shape: a squat viewport silently inflates the region sideways
        // (a 25 km site framed in a wide strip spanned >100 km). A square also matches how
        // coverage is actually consumed — the live map rotates with the compass, so a region
        // must survive any bearing; matching the live map's portrait shape would go blank at
        // the edges the moment it swings 90°.
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            // key(provider) recreates the MapView when the imagery source changes.
            key(provider) {
                RegionPickerMap(
                    styleJson = styleJson,
                    moveRequest = moveRequest,
                    onBoundsChanged = { bounds = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Scrim behind the hint: plain text over satellite imagery is illegible on pale
            // terrain (it disappeared entirely over the Black Rock playa). Matches the
            // semi-transparent overlay idiom FlightMapScreen uses for its map panels.
            Text(
                text = "Pan & zoom to frame the launch area",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .background(mapHintScrim, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Preset sites from the user-editable CSV. Selecting one frames it on the map;
            // the download still takes whatever is visible, so the estimate always matches.
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { presetsExpanded = true },
                    enabled = !downloading && presets.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (presets.isEmpty()) "No preset sites — see ${LaunchSiteRepository.displayPath(context)}"
                        else "Go to preset site…"
                    )
                }
                DropdownMenu(expanded = presetsExpanded, onDismissRequest = { presetsExpanded = false }) {
                    presets.forEach { site ->
                        DropdownMenuItem(
                            text = { Text("${site.name}  (${site.widthKm.toInt()}x${site.heightKm.toInt()} km)") },
                            onClick = {
                                moveRequest = MoveRequest(site.bounds(), System.nanoTime())
                                if (siteName.isBlank()) siteName = site.name
                                presetsExpanded = false
                            },
                        )
                    }
                }
            }

            // Manual coordinate entry — jump straight to a lat/lon.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = latLonText,
                    onValueChange = { latLonText = it; latLonError = false },
                    label = { Text("Lat, Lon") },
                    placeholder = { Text("47.6205, -122.5490") },
                    singleLine = true,
                    isError = latLonError,
                    enabled = !downloading,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val p = parseLatLon(latLonText)
                        if (p == null) latLonError = true
                        else moveRequest = MoveRequest(
                            boundsAround(p.latitude, p.longitude, MANUAL_EXTENT_KM, MANUAL_EXTENT_KM),
                            System.nanoTime(),
                        )
                    },
                    enabled = !downloading && latLonText.isNotBlank(),
                ) { Text("Go") }
            }
            if (latLonError) {
                Text(
                    "Enter as \"lat, lon\" in decimal degrees (e.g. 47.6205, -122.5490).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Imagery source toggle. Mapbox (z22) is enabled only when a token is set.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SatelliteProvider.entries.forEach { p ->
                    val selected = p == provider
                    val onPick = {
                        provider = p
                        maxZoom = maxZoom.coerceIn(14, p.maxOfflineZoom)
                        MapProviderPrefs.set(context, p)
                    }
                    if (selected) {
                        Button(onClick = onPick, enabled = p.available, modifier = Modifier.weight(1f)) { Text(p.displayName) }
                    } else {
                        OutlinedButton(onClick = onPick, enabled = p.available && !downloading, modifier = Modifier.weight(1f)) {
                            Text(if (p.available) p.displayName else "${p.displayName} (no token)")
                        }
                    }
                }
            }

            // Slider + a live inset showing the imagery AT the chosen max zoom, so the number
            // means something: past ~z19 Mapbox is upscaling, and the inset makes that visible
            // (it stops getting sharper) instead of leaving the user to guess.
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Detail (max zoom): z$maxZoom", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = maxZoom.toFloat(),
                        onValueChange = { maxZoom = it.roundToInt() },
                        valueRange = 14f..provider.maxOfflineZoom.toFloat(),
                        steps = (provider.maxOfflineZoom - 14 - 1).coerceAtLeast(0),
                        enabled = !downloading,
                    )
                    Text(
                        text = zoomHint(maxZoom, provider),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    key(provider) {
                        DetailPreviewMap(
                            styleJson = styleJson,
                            center = bounds?.center,
                            zoom = maxZoom,
                            modifier = Modifier.size(104.dp),
                        )
                    }
                    Text(
                        "detail @ z$maxZoom",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Coverage first: sizing a region is a "does this contain the flight + drift
            // footprint?" question, not a "does this match my screen?" one.
            Text(
                text = groundSize?.let { (w, h) -> "Coverage: ≈ %.1f × %.1f km".format(w, h) }
                    ?: "Coverage: —",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "Caching z$minZoom–z$maxZoom  ·  ~${"%,d".format(tiles)} tiles  ·  ~${formatBytes(estBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (estBytes > 1_000_000_000L) {
                Text(
                    "⚠ Over 1 GB — tighten the area or lower the zoom.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = siteName,
                onValueChange = { siteName = it },
                label = { Text("Site name") },
                singleLine = true,
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth(),
            )

            when (val p = progress) {
                is OfflineMapManager.Progress.Downloading -> {
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Downloading… ${(p.fraction * 100).toInt()}%  (${p.completed}/${p.required} tiles, ${formatBytes(p.bytes)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is OfflineMapManager.Progress.Complete ->
                    Text("✓ Downloaded — renders offline on the map.", color = MaterialTheme.colorScheme.primary)
                is OfflineMapManager.Progress.Failed ->
                    Text("✗ ${p.reason}", color = MaterialTheme.colorScheme.error)
                null -> {}
            }

            Button(
                onClick = {
                    val b = bounds ?: return@Button
                    progress = OfflineMapManager.Progress.Downloading(0, 0, 0)
                    manager.downloadRegion(
                        name = siteName.ifBlank { "Launch site" },
                        bounds = b,
                        minZoom = minZoom,
                        maxZoom = maxZoom,
                    ) { p ->
                        progress = p
                        if (p is OfflineMapManager.Progress.Complete) refreshRegions()
                    }
                },
                enabled = bounds != null && !downloading && estBytes <= 1_000_000_000L,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download this area for offline") }

            // Verification aid: clears incidentally-cached browse tiles but keeps downloaded
            // regions, so an offline check can't be fooled by the ambient cache.
            OutlinedButton(
                onClick = { manager.clearAmbientCache { ok -> progress = if (ok) null else OfflineMapManager.Progress.Failed("Cache clear failed") } },
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear ambient cache (offline test)") }

            if (regions.isNotEmpty()) {
                Text("Downloaded regions", style = MaterialTheme.typography.titleSmall)
                Column(modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    regions.forEach { info ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(info.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { manager.deleteRegion(info.region) { refreshRegions() } }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete region")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun zoomHint(z: Int, provider: SatelliteProvider): String = when {
    z >= 20 -> "Maximum detail (${provider.displayName})."
    z >= 19 -> "Bush-level detail."
    z >= 18 -> "Individual trees / vehicles."
    z >= 17 -> "Field features — good for recovery."
    z >= 16 -> "Buildings & roads."
    else -> "Regional context."
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * Non-interactive inset showing the imagery at the region centre at exactly [zoom] — a
 * preview of the detail the chosen max zoom actually buys.
 *
 * Deliberately a separate MapView rather than zooming the picker: the picker's visible
 * bounds ARE the download region, so moving its camera would silently redefine what gets
 * downloaded.
 */
@Composable
private fun DetailPreviewMap(
    styleJson: String,
    center: LatLng?,
    zoom: Int,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.clip(RoundedCornerShape(6.dp)),
        factory = { ctx ->
            MapView(ctx).also { mv ->
                mapView = mv
                mv.onCreate(null)
                mv.getMapAsync { map ->
                    // Inert: it's a readout, not a control.
                    map.uiSettings.apply {
                        setAllGesturesEnabled(false)
                        isLogoEnabled = false
                        isAttributionEnabled = false
                        isCompassEnabled = false
                    }
                    map.setStyle(Style.Builder().fromJson(styleJson)) { mapRef = map }
                }
            }
        },
    )

    LaunchedEffect(mapRef, center, zoom) {
        val map = mapRef ?: return@LaunchedEffect
        val c = center ?: return@LaunchedEffect
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(c).zoom(zoom.toDouble()).build()
            )
        )
    }

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

/**
 * Lightweight MapLibre map for framing a download region. Reports the visible bounds
 * on every camera idle. (Separate from FlightScreen's MapLibreMapView, which carries
 * rocket-specific layers.)
 */
@Composable
private fun RegionPickerMap(
    styleJson: String,
    moveRequest: MoveRequest?,
    onBoundsChanged: (LatLngBounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).also { mv ->
                mapView = mv
                mv.onCreate(null)
                mv.getMapAsync { map ->
                    map.uiSettings.apply { isRotateGesturesEnabled = false; isLogoEnabled = false; isAttributionEnabled = false }
                    map.setStyle(Style.Builder().fromJson(styleJson)) {
                        map.addOnCameraIdleListener {
                            onBoundsChanged(map.projection.visibleRegion.latLngBounds)
                        }
                        // Emit initial bounds.
                        onBoundsChanged(map.projection.visibleRegion.latLngBounds)
                        mapRef = map
                    }
                }
            }
        },
    )

    // Jump the camera when a preset/coordinate is chosen. Keyed on the request (whose id
    // changes per selection) so re-picking the same site re-frames it after panning away.
    LaunchedEffect(moveRequest, mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        val req = moveRequest ?: return@LaunchedEffect
        // newLatLngBounds throws if the view isn't laid out yet — ignore and let the
        // next request (or the user) position the map.
        runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(req.bounds, 48)) }
    }

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

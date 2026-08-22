package com.steampigeon.flightmanager.ui

// ---------------------------------------------------------------------------
// Offline map region download/management for the live FlightMapScreen map.
//
// MapLibre stores offline regions in one app-wide SQLite database, and any MapView
// that requests a tile URL present there is served from disk — so once a region is
// downloaded here, the live satellite map renders it with no connectivity. No extra
// "wiring" beyond matching the tile source URL (both use the same satellite style).
//
// The offline downloader requires the STYLE document over http(s) (asset://, data:,
// and file:// are rejected by its HTTP file-source). To keep downloads self-contained
// on-device — no dev PC, no external hosting — we serve the ~1 KB style from a tiny
// embedded localhost server for the duration of a download. Cleartext to 127.0.0.1 is
// permitted by network_security_config.xml. (Mapbox's hosted style URL removes even
// this once that provider is wired.)
// ---------------------------------------------------------------------------

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * A satellite tile provider (imagery source + offline limits). Both the live map and
 * the download screen resolve the active one via [MapProviderPrefs] so downloads and
 * rendering stay on the same source.
 *
 * NOTE (Mapbox): consuming Mapbox raster tiles through MapLibre is fine for evaluation
 * and gives z22 recovery detail, but Mapbox's ToS generally expects their own SDK for
 * production — especially for the offline bulk-caching done here. Revisit before ship.
 */
enum class SatelliteProvider(
    val displayName: String,
    val minOfflineZoom: Int,
    val maxOfflineZoom: Int,
) {
    // Esri World Imagery: raster tiles to ~z19.
    ESRI("Esri", minOfflineZoom = 10, maxOfflineZoom = 19) {
        override fun styleJson(context: Context) = loadSatelliteStyleJson(context)
        // Measured flat across z13–z17 (21.6–24.0 KB, mean 22.7 KB) on a real download.
        override fun avgTileBytes(z: Int): Long = 23_000L
    },
    // Mapbox Satellite raster tiles via the public token in the tile URL. Source has
    // tiles to z22, but the cap is z20: MapLibre zoom runs ~1 level deeper than Google's
    // (512- vs 256-px tile convention), so z20 ≈ the max satellite detail Google Maps gave
    // us — and each extra level costs 4x storage (22 vs 20 is 16x). Raise to 22 only if
    // more detail than Google's is genuinely wanted.
    MAPBOX("Mapbox", minOfflineZoom = 10, maxOfflineZoom = 20) {
        override fun styleJson(context: Context) = mapboxSatelliteStyleJson(com.steampigeon.flightmanager.BuildConfig.MAPBOX_TOKEN)

        // Measured from a real download (2026-07-16, Puget Sound). Bytes/tile COLLAPSE past
        // z19 — z20 9.5 KB, z21 5.5 KB, z22 4.0 KB vs ~20 KB at z17–18 — because Mapbox's
        // native imagery runs out around z19–20 here and deeper tiles are upscaled blur that
        // JPEG-compresses to nothing. Cheap bytes, but no new detail: paying 4x the tile
        // count per level for interpolation. A flat constant can't model a 5x swing.
        override fun avgTileBytes(z: Int): Long = when {
            z >= 22 -> 4_000L
            z == 21 -> 5_500L
            z == 20 -> 9_500L
            z >= 16 -> 19_000L   // z16–z19 measured 16.4–21.1 KB
            else -> 14_000L      // z≤15, sparse samples averaged ~8–18 KB
        }
    };

    abstract fun styleJson(context: Context): String

    /**
     * Measured average payload of one 256-px SOURCE tile at source zoom [z] — the
     * zoom in the tile URL, which is one deeper than the map zoom (see
     * [OfflineMapManager.sourceZoomOf]). Varies strongly by zoom.
     *
     * Read through [OfflineMapManager.TILE_BYTES_CALIBRATION], which reconciles these
     * historical figures with a real download; see the note there before trusting the
     * absolute values.
     */
    abstract fun avgTileBytes(z: Int): Long

    /** Mapbox is selectable only when a token is configured (secrets.properties → BuildConfig). */
    val available: Boolean
        get() = this != MAPBOX || com.steampigeon.flightmanager.BuildConfig.MAPBOX_TOKEN.isNotBlank()
}

/** Builds a MapLibre style whose raster source is Mapbox Satellite (256-px JPEG, to z22). */
fun mapboxSatelliteStyleJson(token: String): String = """
{
  "version": 8,
  "name": "Mapbox Satellite",
  "sources": {
    "satellite": {
      "type": "raster",
      "tiles": ["https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}.jpg?access_token=$token"],
      "tileSize": 256,
      "minzoom": 0,
      "maxzoom": 22,
      "attribution": "© Mapbox © Maxar"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#0b0f14" } },
    { "id": "satellite", "type": "raster", "source": "satellite", "paint": { "raster-opacity": 1.0 } }
  ]
}
""".trimIndent()

/** App-wide selected satellite provider, persisted in SharedPreferences. */
object MapProviderPrefs {
    private const val PREFS = "map_prefs"
    private const val KEY = "provider"

    fun get(context: Context): SatelliteProvider {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val p = SatelliteProvider.entries.firstOrNull { it.name == name } ?: SatelliteProvider.ESRI
        return if (p.available) p else SatelliteProvider.ESRI
    }

    fun set(context: Context, provider: SatelliteProvider) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, provider.name).apply()
    }
}

/**
 * The one in-flight region download, held for the life of the process.
 *
 * A download does NOT belong to the download screen. It runs inside MapLibre's process-wide
 * OfflineManager and keeps going whether or not [DownloadMapScreen] is composed — nothing on
 * the exit path sets the region inactive. Screen-local state could not represent that: leaving
 * the screen threw the progress away, so returning showed a blank slate with the Download
 * button live again, inviting a second overlapping region on top of the one still running.
 *
 * Terminal results (Complete/Failed/Canceled) are kept, not cleared, so a download that ends
 * while the user is elsewhere still reports itself when they come back.
 */
object OfflineDownloadRepository {
    /** The active or most recent download, or null if none has been started this session. */
    data class Download(
        val name: String,
        val progress: OfflineMapManager.Progress,
        /**
         * True once the region exists and the download can actually be stopped. Part of the
         * published state rather than a property over the handle below, so that Compose sees
         * it change — a plain field would leave Cancel grayed out until some unrelated redraw.
         */
        val cancelable: Boolean = false,
    )

    private val _current = MutableStateFlow<Download?>(null)
    val current: StateFlow<Download?> = _current.asStateFlow()

    /**
     * Stops the running download. Armed once the region exists — which is why cancel lives here
     * and not on the manager: the region handle has to outlive the screen that started it, or a
     * download resumed-into from a fresh screen instance would have nothing to cancel.
     */
    private var stop: (() -> Unit)? = null

    /** True while tiles are still being fetched — the gate on starting another download. */
    val isRunning: Boolean
        get() = _current.value?.progress is OfflineMapManager.Progress.Downloading

    internal fun publish(name: String, progress: OfflineMapManager.Progress) {
        if (progress !is OfflineMapManager.Progress.Downloading) stop = null
        _current.value = Download(name, progress, cancelable = stop != null)
    }

    internal fun armCancel(block: () -> Unit) {
        stop = block
        _current.value = _current.value?.copy(cancelable = true)
    }

    /** User-requested stop. The partial region is kept in the DB and can be resumed. */
    fun cancel() {
        stop?.invoke()
    }

    /** Drops a finished result once the user has seen it. No-op while a download is running. */
    fun clearFinished() {
        if (!isRunning) _current.value = null
    }
}

class OfflineMapManager(
    private val context: Context,
    private val provider: SatelliteProvider = SatelliteProvider.ESRI,
) {
    private val styleJson: String by lazy { provider.styleJson(context) }
    private val offlineManager: OfflineManager by lazy { OfflineManager.getInstance(context) }

    sealed interface Progress {
        /**
         * [required] is only a real total once [precise] is true; before that MapLibre reports
         * it as a lower bound, so a percentage computed from it reads far too high (often 100%)
         * in the opening seconds. [fraction] is null until it can be trusted — callers should
         * show an indeterminate state rather than a number that will visibly go backwards.
         */
        data class Downloading(
            val completed: Long,
            val required: Long,
            val bytes: Long,
            val precise: Boolean,
        ) : Progress {
            val fraction: Float?
                get() = if (!precise || required <= 0L) null
                else (completed.toFloat() / required).coerceIn(0f, 1f)
        }
        data object Complete : Progress
        data class Failed(val reason: String) : Progress
        /** Stopped by the user. Whatever had downloaded stays in the DB, resumable. */
        data object Canceled : Progress
    }

    /**
     * Downloads all tiles for [bounds] across [minZoom]..[maxZoom] into the offline DB.
     * Serves the style from an embedded localhost server (started here, stopped when the
     * region reaches a terminal state).
     *
     * Progress goes to [OfflineDownloadRepository] rather than a caller-supplied callback:
     * the download outlives whatever screen started it, so its status has to live somewhere
     * that outlives the screen too.
     */
    fun downloadRegion(
        name: String,
        bounds: LatLngBounds,
        minZoom: Int,
        maxZoom: Int,
    ) {
        if (OfflineDownloadRepository.isRunning) return
        fun publish(p: Progress) = OfflineDownloadRepository.publish(name, p)

        // Claim the slot before any async work, so a second tap can't slip through.
        publish(Progress.Downloading(0, 0, 0, precise = false))

        val server = LocalStyleServer(styleJson)
        try {
            server.start()
        } catch (e: IOException) {
            publish(Progress.Failed("Could not start local style server: ${e.message}"))
            return
        }

        val definition = OfflineTilePyramidRegionDefinition(
            server.styleUrl(),
            bounds,
            minZoom.toDouble(),
            maxZoom.toDouble(),
            context.resources.displayMetrics.density,
        )
        // The provider is recorded so a later resume rebuilds the right style. Without it a
        // region downloaded from Mapbox and resumed while Esri is selected would finish with
        // two different imagery sources stitched into one region.
        val metadata = JSONObject()
            .put(METADATA_NAME, name)
            .put(METADATA_PROVIDER, provider.name)
            .toString().toByteArray()

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) = activate(region, server, ::publish)

                override fun onError(error: String) {
                    server.stop()
                    publish(Progress.Failed(error))
                }
            },
        )
    }

    /**
     * Restarts an interrupted download for [info]'s region, picking up where it stopped.
     *
     * The catch is the style URL. A region's definition is immutable and permanently records the
     * `127.0.0.1:<port>` URL from the session that created it, with an ephemeral port that is
     * long dead. So the server is re-bound to that exact port rather than a fresh one. If the
     * port is taken the resume still proceeds: the style is usually already in the offline DB
     * from the first attempt, in which case no fetch happens and only the tiles are refetched.
     */
    fun resumeRegion(info: RegionInfo) {
        if (OfflineDownloadRepository.isRunning) return
        fun publish(p: Progress) = OfflineDownloadRepository.publish(info.name, p)
        publish(Progress.Downloading(0, 0, 0, precise = false))

        // Resume on the source the region was started with, not whatever is selected now.
        val json = (info.provider ?: provider).styleJson(context)
        val server = LocalStyleServer(json)
        runCatching { server.start(stylePortOf(info.region.definition.styleURL)) }

        activate(info.region, server, ::publish)
    }

    /**
     * Attaches the progress observer to [region] and starts it downloading. Shared by a fresh
     * download and a resume — MapLibre makes no distinction between the two, it just activates.
     */
    private fun activate(region: OfflineRegion, server: LocalStyleServer, publish: (Progress) -> Unit) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isDefinitelyComplete()) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    server.stop()
                    publish(Progress.Complete)
                } else {
                    publish(
                        Progress.Downloading(
                            status.completedResourceCount,
                            status.requiredResourceCount,
                            status.completedResourceSize,
                            status.isRequiredResourceCountPrecise,
                        )
                    )
                }
            }

            override fun onError(error: OfflineRegionError) {
                server.stop()
                publish(Progress.Failed("${error.reason}: ${error.message}"))
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                server.stop()
                publish(Progress.Failed("Tile count limit exceeded: $limit"))
            }
        })
        OfflineDownloadRepository.armCancel {
            // Going inactive also stops observer callbacks (MapLibre suppresses them for an
            // inactive region unless asked otherwise), so this publish is the last word.
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            server.stop()
            publish(Progress.Canceled)
        }
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /**
     * A region in the offline database together with how much of it is actually on disk.
     *
     * The row exists from the moment a download is created, so presence in this list means
     * "started", never "finished" — [complete] is what says the region will render offline.
     */
    data class RegionInfo(
        val region: OfflineRegion,
        val name: String,
        /**
         * Bytes this region has downloaded. Overlapping regions share tiles, so these do not
         * sum to the size of the offline database.
         */
        val bytes: Long,
        /** null when the status query failed — reported as unknown rather than guessed. */
        val complete: Boolean?,
        /** Downloaded share, or null while the required total is still a lower bound. */
        val fraction: Float?,
        /** Source this region was downloaded from; null for regions predating that metadata. */
        val provider: SatelliteProvider?,
    )

    /**
     * Lists every region with its download status.
     *
     * Each status is a separate async query, so results are gathered and emitted once, when
     * the last one lands. MapLibre delivers these callbacks on the main thread, which is what
     * makes the plain counter below safe.
     */
    fun listRegions(onResult: (List<RegionInfo>) -> Unit) {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                val list = regions.orEmpty()
                if (list.isEmpty()) {
                    onResult(emptyList())
                    return
                }
                val out = arrayOfNulls<RegionInfo>(list.size)
                var remaining = list.size

                fun settle(index: Int, info: RegionInfo) {
                    out[index] = info
                    if (--remaining == 0) onResult(out.filterNotNull())
                }

                list.forEachIndexed { i, region ->
                    val meta = runCatching { JSONObject(String(region.metadata)) }.getOrNull()
                    val name = meta?.optString(METADATA_NAME).orEmpty().ifBlank { "(unnamed)" }
                    val regionProvider = meta?.optString(METADATA_PROVIDER)
                        ?.let { saved -> SatelliteProvider.entries.firstOrNull { it.name == saved } }
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status == null) {
                                settle(i, RegionInfo(region, name, 0L, null, null, regionProvider))
                                return
                            }
                            settle(
                                i,
                                RegionInfo(
                                    region = region,
                                    name = name,
                                    bytes = status.completedResourceSize,
                                    complete = status.isDefinitelyComplete(),
                                    fraction = if (status.isRequiredResourceCountPrecise && status.requiredResourceCount > 0L)
                                        (status.completedResourceCount.toFloat() / status.requiredResourceCount).coerceIn(0f, 1f)
                                    else null,
                                    provider = regionProvider,
                                ),
                            )
                        }

                        override fun onError(error: String?) {
                            settle(i, RegionInfo(region, name, 0L, null, null, regionProvider))
                        }
                    })
                }
            }
            override fun onError(error: String) = onResult(emptyList())
        })
    }

    fun deleteRegion(region: OfflineRegion, onDone: (Boolean) -> Unit) {
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = onDone(true)
            override fun onError(error: String) = onDone(false)
        })
    }

    companion object {
        private const val METADATA_NAME = "site_name"
        private const val METADATA_PROVIDER = "provider"

        /**
         * The port out of a region's recorded `http://127.0.0.1:<port>/style.json`, or 0 (any
         * free port) if it can't be read — a region created before this scheme, or a hosted
         * style URL, in which case no local server is needed anyway.
         */
        private fun stylePortOf(styleUrl: String?): Int =
            Regex("""^http://127\.0\.0\.1:(\d+)/""").find(styleUrl.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        /**
         * The SOURCE tile zoom MapLibre fetches when the map is at [mapZoom].
         *
         * The style declares `"tileSize": 256` while MapLibre's logical tile grid is
         * 512, so it fetches source tiles one level deeper than the map zoom asks
         * for — four tiles where this arithmetic used to count one.
         *
         * That is the whole of the old under-count. A 22 x 22 km region estimated
         * 12,484 tiles against MapLibre's own 49,155 resources: a ratio of 3.94,
         * which is one zoom level and not a coincidence. The provider's note about
         * detail ("MapLibre zoom runs ~1 level deeper than Google's") was the same
         * fact, applied to what the user sees but never to the count.
         *
         * The DOWNLOAD is unaffected — it is defined by the map-zoom range handed to
         * OfflineTilePyramidRegionDefinition, and always fetched these tiles. Only
         * the estimate was wrong.
         */
        fun sourceZoomOf(mapZoom: Int): Int = mapZoom + 1

        /**
         * Source tiles fetched for [bounds] over the map-zoom range [minZoom]..[maxZoom].
         */
        fun tileCount(bounds: LatLngBounds, minZoom: Int, maxZoom: Int): Long {
            var total = 0L
            for (z in minZoom..maxZoom) total += tileCountAtZoom(bounds, sourceZoomOf(z))
            return total
        }

        /** Tiles covering [bounds] at a single zoom [z]. */
        fun tileCountAtZoom(bounds: LatLngBounds, z: Int): Long {
            val n = 1 shl z
            val xMin = lonToTileX(bounds.longitudeWest, n)
            val xMax = lonToTileX(bounds.longitudeEast, n)
            val yMin = latToTileY(bounds.latitudeNorth, n)   // north = smaller y
            val yMax = latToTileY(bounds.latitudeSouth, n)
            val cols = (xMax - xMin + 1).coerceAtLeast(1)
            val rows = (yMax - yMin + 1).coerceAtLeast(1)
            return cols.toLong() * rows.toLong()
        }

        /**
         * Correction on [SatelliteProvider.avgTileBytes], derived from one real download.
         *
         * The per-zoom figures were measured before [sourceZoomOf] existed, so whatever
         * total they were divided by was the old under-count. Rather than rewrite five
         * numbers that would then read as measurements, the historical table is kept and
         * the one factor that reconciles it with reality is named here.
         *
         * Anchor: a 9.1 x 9.1 km region at z10–z17 near 47.6 N downloaded **139 MB**.
         * At the corrected count (10,876 source tiles) the untouched table predicts
         * ~205 MB, so 139/205 = 0.68 — i.e. a real tile there averages ~12.8 kB against
         * the table's 19 kB for that depth.
         *
         * ONE anchor, at ONE depth: 75% of that region's tiles are the single deepest
         * level, so this pins source z18 and inherits the SHAPE of everything else —
         * including the collapse past z20, which no measurement here reaches. It is a
         * calibration, not a measurement, and a download at a different zoom range would
         * do better than refine it. Esri gets the same factor: both tables were built the
         * same way in the same commit, and correcting only the one with an anchor would
         * leave the other ~3.9x high.
         */
        private const val TILE_BYTES_CALIBRATION = 0.68

        /**
         * Estimated bytes for [bounds] across the map-zoom range [minZoom]..[maxZoom],
         * summed **per zoom** over the SOURCE tiles actually fetched (see [sourceZoomOf]).
         *
         * Not tiles x one constant: measured tile size swings ~5x across the zoom range
         * (Mapbox ~20 KB at z17 vs ~4 KB at z22, where imagery is upscaled), and the deepest
         * level is ~75% of all tiles — so a flat average badly misprices whichever end the
         * user picks.
         *
         * The old estimate was ~2.7x LOW, and low is the dangerous direction here: the
         * 1 GB guard reads this number, so an under-estimate waves through a region that
         * is really over budget rather than refusing one that would have fit.
         */
        fun estimateBytes(
            bounds: LatLngBounds,
            minZoom: Int,
            maxZoom: Int,
            provider: SatelliteProvider,
        ): Long {
            var total = 0.0
            for (z in minZoom..maxZoom) {
                val source = sourceZoomOf(z)
                total += tileCountAtZoom(bounds, source).toDouble() *
                        provider.avgTileBytes(source) * TILE_BYTES_CALIBRATION
            }
            return total.toLong()
        }

        private fun lonToTileX(lon: Double, n: Int): Int =
            floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

        private fun latToTileY(lat: Double, n: Int): Int {
            val latRad = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
            val y = (1.0 - asinh(tan(latRad)) / PI) / 2.0 * n
            return floor(y).toInt().coerceIn(0, n - 1)
        }
    }
}

/**
 * Whether the region is finished, without the false positive built into [OfflineRegionStatus.isComplete].
 *
 * `isComplete` is `completedResourceCount >= requiredResourceCount`, and the required count is
 * only a real total once [OfflineRegionStatus.isRequiredResourceCountPrecise] flips true — until
 * the style and tile sources resolve it is a lower bound, initially zero. A region that has just
 * been created, or one interrupted before it got going, therefore reports 0 >= 0 and calls itself
 * complete. Requiring precision costs nothing for a genuinely finished region (its sources are by
 * definition downloaded) and stops a barely-started one from listing as fully cached.
 */
private fun OfflineRegionStatus.isDefinitelyComplete(): Boolean =
    isRequiredResourceCountPrecise && requiredResourceCount > 0L && isComplete

/**
 * Single-purpose localhost HTTP/1.1 server that returns [styleJson] for any request.
 * Bound to 127.0.0.1 for the duration of a download — on an ephemeral port for a new region,
 * or on the region's original port when resuming one (see [OfflineMapManager.resumeRegion]).
 */
private class LocalStyleServer(private val styleJson: String) {
    private var serverSocket: ServerSocket? = null
    var port: Int = -1
        private set

    /**
     * Binds [port], or any free port when 0. A resume passes the port baked into the region's
     * immutable definition, since that is the only URL that region will ever ask for.
     */
    fun start(requestedPort: Int = 0) {
        val ss = ServerSocket(requestedPort, 4, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        Thread {
            val body = styleJson.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            while (!ss.isClosed) {
                try {
                    ss.accept().use { client ->
                        // Consume the request head with a BLOCKING read before responding.
                        // (available() would usually be 0 right after accept() — the request
                        // bytes haven't landed yet — so responding immediately and closing
                        // races the client's write and can reset the connection.)
                        client.soTimeout = 3000
                        val input = client.getInputStream()
                        val buf = ByteArray(4096)
                        try {
                            input.read(buf)   // blocks until the GET arrives or times out
                        } catch (_: Exception) {
                            // timeout / early close — still answer; the body is all that matters
                        }
                        client.getOutputStream().apply {
                            write(header.toByteArray(Charsets.US_ASCII))
                            write(body)
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    // socket closed on stop(), or a client aborted — either way keep looping/exit
                }
            }
        }.apply { isDaemon = true; name = "offline-style-server"; start() }
    }

    fun styleUrl(): String = "http://127.0.0.1:$port/style.json"

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}

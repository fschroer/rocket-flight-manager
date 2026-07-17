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

    /** Measured average payload of one 256-px tile at [z]. Varies strongly by zoom. */
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

class OfflineMapManager(
    private val context: Context,
    private val provider: SatelliteProvider = SatelliteProvider.ESRI,
) {
    private val styleJson: String by lazy { provider.styleJson(context) }
    private val offlineManager: OfflineManager by lazy { OfflineManager.getInstance(context) }

    sealed interface Progress {
        data class Downloading(val completed: Long, val required: Long, val bytes: Long) : Progress {
            val fraction: Float get() = if (required == 0L) 0f else (completed.toFloat() / required).coerceIn(0f, 1f)
        }
        data object Complete : Progress
        data class Failed(val reason: String) : Progress
    }

    /**
     * Downloads all tiles for [bounds] across [minZoom]..[maxZoom] into the offline DB.
     * Serves the style from an embedded localhost server (started here, stopped when the
     * region reaches a terminal state).
     */
    fun downloadRegion(
        name: String,
        bounds: LatLngBounds,
        minZoom: Int,
        maxZoom: Int,
        onProgress: (Progress) -> Unit,
    ) {
        val server = LocalStyleServer(styleJson)
        try {
            server.start()
        } catch (e: IOException) {
            onProgress(Progress.Failed("Could not start local style server: ${e.message}"))
            return
        }

        val definition = OfflineTilePyramidRegionDefinition(
            server.styleUrl(),
            bounds,
            minZoom.toDouble(),
            maxZoom.toDouble(),
            context.resources.displayMetrics.density,
        )
        val metadata = JSONObject().put(METADATA_NAME, name).toString().toByteArray()

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (status.isComplete) {
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                server.stop()
                                onProgress(Progress.Complete)
                            } else {
                                onProgress(
                                    Progress.Downloading(
                                        status.completedResourceCount,
                                        status.requiredResourceCount,
                                        status.completedResourceSize,
                                    )
                                )
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            server.stop()
                            onProgress(Progress.Failed("${error.reason}: ${error.message}"))
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            server.stop()
                            onProgress(Progress.Failed("Tile count limit exceeded: $limit"))
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    server.stop()
                    onProgress(Progress.Failed(error))
                }
            },
        )
    }

    data class RegionInfo(val region: OfflineRegion, val name: String, val bytes: Long)

    fun listRegions(onResult: (List<RegionInfo>) -> Unit) {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                val list = regions.orEmpty().map { r ->
                    val nm = runCatching { JSONObject(String(r.metadata)).getString(METADATA_NAME) }
                        .getOrDefault("(unnamed)")
                    RegionInfo(r, nm, 0L)
                }
                onResult(list)
            }
            override fun onError(error: String) = onResult(emptyList())
        })
    }

    /**
     * Drops the ambient (LRU browse) cache while leaving downloaded offline regions intact.
     * Useful to prove offline coverage honestly: after this, anything that still renders with
     * the network off came from a downloaded region, not from incidentally-cached browsing.
     */
    fun clearAmbientCache(onDone: (Boolean) -> Unit) {
        offlineManager.clearAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = onDone(true)
            override fun onError(message: String) = onDone(false)
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

        /** Number of 256-px web-mercator tiles covering [bounds] across [minZoom]..[maxZoom]. */
        fun tileCount(bounds: LatLngBounds, minZoom: Int, maxZoom: Int): Long {
            var total = 0L
            for (z in minZoom..maxZoom) total += tileCountAtZoom(bounds, z)
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
         * Estimated bytes for [bounds] across [minZoom]..[maxZoom], summed **per zoom**.
         *
         * Not tiles x one constant: measured tile size swings ~5x across the zoom range
         * (Mapbox ~20 KB at z17 vs ~4 KB at z22, where imagery is upscaled), and the deepest
         * level is ~75% of all tiles — so a flat average badly misprices whichever end the
         * user picks.
         */
        fun estimateBytes(
            bounds: LatLngBounds,
            minZoom: Int,
            maxZoom: Int,
            provider: SatelliteProvider,
        ): Long {
            var total = 0L
            for (z in minZoom..maxZoom) total += tileCountAtZoom(bounds, z) * provider.avgTileBytes(z)
            return total
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
 * Single-purpose localhost HTTP/1.1 server that returns [styleJson] for any request.
 * Bound to 127.0.0.1 on an ephemeral port; used only during an offline download.
 */
private class LocalStyleServer(private val styleJson: String) {
    private var serverSocket: ServerSocket? = null
    var port: Int = -1
        private set

    fun start() {
        val ss = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
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

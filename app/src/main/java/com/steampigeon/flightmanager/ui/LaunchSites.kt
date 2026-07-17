package com.steampigeon.flightmanager.ui

// ---------------------------------------------------------------------------
// Preset launch sites for the offline map download screen.
//
// Sites are read from a user-editable CSV in the app's external files dir
// (Android/data/<pkg>/files/launch_sites.csv) — reachable over USB or a file
// manager with no permissions and no rebuild. A template is seeded there from
// assets on first use; deleting the file re-seeds it.
// ---------------------------------------------------------------------------

import android.content.Context
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** A named area to cache, centred on [lat]/[lon] with total extents in km. */
data class LaunchSite(
    val name: String,
    val lat: Double,
    val lon: Double,
    val widthKm: Double,
    val heightKm: Double,
) {
    fun bounds(): LatLngBounds = boundsAround(lat, lon, widthKm, heightKm)
}

/**
 * Approximate ground size of a bounds as (widthKm, heightKm).
 *
 * Coverage is the question that actually matters when sizing a region — the live map's
 * viewport is a rotating window that pans over it, not a shape to match — so surface this
 * next to the tile/byte estimate.
 */
fun LatLngBounds.groundSizeKm(): Pair<Double, Double> {
    val heightKm = (latitudeNorth - latitudeSouth) * 111.32
    val centerLat = (latitudeNorth + latitudeSouth) / 2.0
    val widthKm = (longitudeEast - longitudeWest) * 111.32 * cos(centerLat * PI / 180.0)
    return Pair(abs(widthKm), abs(heightKm))
}

/** Bounding box of [widthKm] x [heightKm] centred on a point. */
fun boundsAround(lat: Double, lon: Double, widthKm: Double, heightKm: Double): LatLngBounds {
    val latDelta = (heightKm / 2.0) / 111.32
    // Longitude degrees shrink with latitude; guard the cos term near the poles.
    val lonDelta = (widthKm / 2.0) / (111.32 * cos(lat * PI / 180.0).coerceAtLeast(0.01))
    return LatLngBounds.Builder()
        .include(LatLng((lat + latDelta).coerceIn(-85.0, 85.0), lon + lonDelta))
        .include(LatLng((lat - latDelta).coerceIn(-85.0, 85.0), lon - lonDelta))
        .build()
}

object LaunchSiteRepository {

    private const val FILE_NAME = "launch_sites.csv"

    /** Extent used when a site line gives only a centre point. */
    const val DEFAULT_EXTENT_KM = 10.0

    /** The user-editable CSV. Shown in the UI so the file is findable. */
    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)

    /** Human-readable location to surface in the UI. */
    fun displayPath(context: Context): String =
        "Android/data/${context.packageName}/files/$FILE_NAME"

    /**
     * Loads sites, seeding the editable copy from the bundled template if absent.
     * Malformed lines are skipped rather than failing the whole file.
     */
    fun load(context: Context): List<LaunchSite> {
        val f = file(context)
        if (!f.exists()) {
            runCatching {
                f.parentFile?.mkdirs()
                context.assets.open(FILE_NAME).use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        val text = runCatching { f.readText() }
            .getOrElse {
                // External dir unavailable — fall back to the bundled template.
                runCatching { context.assets.open(FILE_NAME).bufferedReader().use { it.readText() } }
                    .getOrDefault("")
            }
        return parse(text)
    }

    fun parse(text: String): List<LaunchSite> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { parseLine(it) }
            .toList()

    /**
     * `name,lat,lon[,width_km[,height_km]]`
     *
     * Parses the *trailing* numeric fields as the values so a name may contain commas
     * ("Brothers, OR"). Extents are optional: height defaults to width (square), and both
     * default to [DEFAULT_EXTENT_KM] — writing just `name,lat,lon` is the obvious thing to
     * do and must not silently drop the line.
     */
    internal fun parseLine(line: String): LaunchSite? {
        val parts = line.split(',').map { it.trim() }
        if (parts.size < 3) return null   // need at least a name plus lat,lon

        fun build(n: Int): LaunchSite? {
            // Require at least one leading field for the name; this also guarantees
            // nums has exactly n entries, so the indexing below is safe.
            if (parts.size <= n) return null
            val nums = parts.takeLast(n).map { it.toDoubleOrNull() ?: return null }
            // Re-join with ", " since each field was trimmed ("Black Rock, NV").
            val name = parts.dropLast(n).joinToString(", ").trim()
            if (name.isEmpty()) return null
            val lat = nums[0]
            val lon = nums[1]
            val w = if (n >= 3) nums[2] else DEFAULT_EXTENT_KM
            val h = if (n == 4) nums[3] else w
            if (abs(lat) > 90 || abs(lon) > 180) return null
            if (w <= 0 || h <= 0) return null
            return LaunchSite(name, lat, lon, w, h)
        }

        // Most specific first: name,lat,lon,w,h → name,lat,lon,size → name,lat,lon.
        return build(4) ?: build(3) ?: build(2)
    }
}

/** Parses "lat, lon" (comma- or space-separated). Returns null if invalid/out of range. */
fun parseLatLon(text: String): LatLng? {
    val parts = text.trim().split(',', ' ', '\t')
        .filter { it.isNotBlank() }
        .mapNotNull { it.trim().toDoubleOrNull() }
    if (parts.size != 2) return null
    val (lat, lon) = parts
    if (abs(lat) > 90 || abs(lon) > 180) return null
    return LatLng(lat, lon)
}

package com.steampigeon.flightmanager.data.simflight

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the Simulated Flight screen keeps on the phone (locator ADR-0041
 * amendment, decisions A4): the rockets the flyer has described, saved by name,
 * and every motor downloaded or imported, so the screen works at a field with
 * no signal.
 *
 * Motors are kept as the original RASP or RockSim text, one file each, and
 * parsed on load by [MotorFiles]; rockets are one JSON file.  Everything is in
 * the app's private files directory.
 */
class SimFlightStore(context: Context) {
    private val dir = File(context.filesDir, "simflight").apply { mkdirs() }
    private val motorDir = File(dir, "motors").apply { mkdirs() }
    private val rocketsFile = File(dir, "rockets.json")
    private val lastFile = File(dir, "last_rocket.txt")

    // ── Motors ──────────────────────────────────────────────────────────────

    private fun motorFile(id: String) = File(motorDir, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt")

    /** Keeps [text] (a .eng or .rse file) under [id] and returns the motor, or null if it does not parse. */
    fun saveMotor(id: String, text: String): SimMotor? {
        val m = MotorFiles.parse(text, id) ?: return null
        motorFile(id).writeText(text)
        return m
    }

    fun loadMotor(id: String?): SimMotor? {
        if (id == null) return null
        val f = motorFile(id)
        if (!f.exists()) return null
        return try { MotorFiles.parse(f.readText(), id) } catch (e: Exception) { null }
    }

    /** Every cached motor, by class then designation. */
    fun cachedMotors(): List<SimMotor> =
        (motorDir.listFiles() ?: emptyArray()).mapNotNull { f ->
            val id = idOf(f) ?: return@mapNotNull null
            try { MotorFiles.parse(f.readText(), id) } catch (e: Exception) { null }
        }.sortedWith(compareBy({ it.impulseClass }, { it.designation }))

    // The file name is the id with unsafe characters replaced; the original id
    // is the first line of a sidecar so it survives the replacement.
    private fun idOf(f: File): String? {
        if (!f.name.endsWith(".txt")) return null
        val side = File(f.path.removeSuffix(".txt") + ".id")
        return if (side.exists()) side.readText().trim() else f.name.removeSuffix(".txt")
    }

    fun saveMotorWithId(id: String, text: String): SimMotor? {
        val m = saveMotor(id, text) ?: return null
        File(motorFile(id).path.removeSuffix(".txt") + ".id").writeText(id)
        return m
    }

    // ── Rockets ─────────────────────────────────────────────────────────────

    fun rockets(): List<SimRocket> = try {
        if (!rocketsFile.exists()) emptyList() else {
            val a = JSONArray(rocketsFile.readText())
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.sortedBy { it.name.lowercase() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "rockets.json unreadable", e); emptyList()
    }

    /** Saves under its name, replacing a rocket of the same name. */
    fun saveRocket(r: SimRocket) {
        if (r.name.isBlank()) return
        val all = rockets().filter { it.name != r.name } + r
        rocketsFile.writeText(JSONArray(all.map { toJson(it) }).toString())
        lastFile.writeText(r.name)
    }

    fun deleteRocket(name: String) {
        rocketsFile.writeText(JSONArray(rockets().filter { it.name != name }.map { toJson(it) }).toString())
    }

    fun lastRocket(): SimRocket? {
        val name = if (lastFile.exists()) lastFile.readText().trim() else return null
        return rockets().firstOrNull { it.name == name }
    }

    private fun toJson(r: SimRocket) = JSONObject().apply {
        put("name", r.name); put("dryMassG", r.dryMassG); put("diameterMm", r.diameterMm.toDouble())
        put("lengthMm", r.lengthMm); put("stabilityCal", r.stabilityCal.toDouble())
        put("cd", JSONArray(r.cd.map { it.toDouble() })); put("motorId", r.motorId ?: JSONObject.NULL)
        put("drogueMps", r.drogueMps); put("mainMps", r.mainMps)
        put("railLengthCm", r.railLengthCm); put("railAngleTenths", r.railAngleTenths)
        put("launchAzDeg", r.launchAzDeg); put("tempC", r.tempC ?: JSONObject.NULL)
        put("windMps", r.windMps); put("windFromDeg", r.windFromDeg)
    }

    private fun fromJson(o: JSONObject): SimRocket {
        val d = SimRocket()
        val cdA = o.optJSONArray("cd")
        val cd = if (cdA != null && cdA.length() == Drag.MACH_GRID.size)
            (0 until cdA.length()).map { cdA.getDouble(it).toFloat() } else d.cd
        return SimRocket(
            name = o.optString("name", ""), dryMassG = o.optInt("dryMassG", d.dryMassG),
            diameterMm = o.optDouble("diameterMm", d.diameterMm.toDouble()).toFloat(), cd = cd,
            lengthMm = o.optInt("lengthMm", d.lengthMm),
            stabilityCal = o.optDouble("stabilityCal", d.stabilityCal.toDouble()).toFloat(),
            motorId = if (o.isNull("motorId")) null else o.optString("motorId"),
            drogueMps = o.optInt("drogueMps", d.drogueMps), mainMps = o.optInt("mainMps", d.mainMps),
            railLengthCm = o.optInt("railLengthCm", d.railLengthCm),
            railAngleTenths = o.optInt("railAngleTenths", d.railAngleTenths),
            launchAzDeg = o.optInt("launchAzDeg", d.launchAzDeg), tempC = if (o.isNull("tempC")) null else o.optInt("tempC"),
            windMps = o.optInt("windMps", d.windMps), windFromDeg = o.optInt("windFromDeg", d.windFromDeg),
        )
    }

    companion object { private const val TAG = "SimFlightStore" }
}

/** One thrustcurve.org search result. */
data class MotorHit(
    val motorId: String,
    val manufacturer: String,
    val designation: String,
    val commonName: String,
    val impulseClass: String,
    val diameterMm: Float,
    val totalImpulseNs: Float,
    val burnTimeS: Float,
)

/**
 * thrustcurve.org's public API (https://www.thrustcurve.org/info/api.html):
 * `search.json` to find motors, `download.json` for a motor's RASP file, which
 * arrives base64-encoded.  Written from the published API without having been
 * run against it from this codebase's build environment (it could not reach the
 * site), so the first search on a phone is its test.
 */
object ThrustCurveApi {
    private const val BASE = "https://www.thrustcurve.org/api/v1/"
    private const val TAG = "ThrustCurveApi"

    /**
     * A class letter alone ("H") searches by impulse class; anything else by
     * common name ("H128"), falling back to designation ("H128W") when that
     * finds nothing.
     */
    suspend fun search(query: String): List<MotorHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val first = if (Regex("^[A-Oa-o]$").matches(q))
            JSONObject().put("impulseClass", q.uppercase())
        else JSONObject().put("commonName", q)
        var hits = parseHits(post("search.json", first.put("maxResults", 50)))
        if (hits.isEmpty() && first.has("commonName"))
            hits = parseHits(post("search.json", JSONObject().put("designation", q).put("maxResults", 50)))
        hits
    }

    /** The motor's RASP text, or null. */
    suspend fun downloadEng(motorId: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("motorIds", JSONArray().put(motorId)).put("format", "RASP").put("data", "file")
        val r = post("download.json", body)?.optJSONArray("results") ?: return@withContext null
        for (i in 0 until r.length()) {
            val data = r.getJSONObject(i).optString("data", "")
            if (data.isNotEmpty()) return@withContext String(Base64.decode(data, Base64.DEFAULT))
        }
        null
    }

    private fun parseHits(o: JSONObject?): List<MotorHit> {
        val a = o?.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { m ->
            MotorHit(
                motorId = m.optString("motorId"),
                manufacturer = m.optString("manufacturer"),
                designation = m.optString("designation"),
                commonName = m.optString("commonName"),
                impulseClass = m.optString("impulseClass"),
                diameterMm = m.optDouble("diameter", 0.0).toFloat(),
                totalImpulseNs = m.optDouble("totImpulseNs", 0.0).toFloat(),
                burnTimeS = m.optDouble("burnTimeS", 0.0).toFloat(),
            )
        }.filter { it.motorId.isNotEmpty() }
    }

    private fun post(path: String, body: JSONObject): JSONObject? = try {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) { Log.w(TAG, "$path -> ${c.responseCode}"); null }
        else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        Log.w(TAG, "$path failed", e); null
    }
}

package com.steampigeon.flightmanager.ui

/**
 * How the map's statistics panel says "these numbers are old".
 *
 * The panel used to vanish 2 s after the last locator message. That threw away
 * the one thing a flyer needs most when the link drops — the last altitude,
 * flight state and position the rocket reported — at exactly the moment they
 * stop being able to get a new one. It now stays up showing the last values,
 * dimmed, with a header that says how long ago they were heard.
 *
 * Kept free of Android so the formatting can be tested on the JVM.
 */
object StaleTelemetry {
    /**
     * Older than this and the panel's values are shown as stale. The same 2 s the
     * map screen uses for "is the locator live" everywhere else, so the panel goes
     * stale at the moment the banner and the rocket marker do.
     */
    const val STALE_AFTER_MS = 2_000L

    fun isStale(ageMs: Long): Boolean = ageMs >= STALE_AFTER_MS

    /** Compact age for the panel header: `12 s`, `4 min`, `1 h 5 min`. */
    fun shortAge(ageMs: Long): String {
        val s = (ageMs.coerceAtLeast(0L) / 1000L)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> {
                val m = (s % 3600) / 60
                if (m == 0L) "${s / 3600} h" else "${s / 3600} h $m min"
            }
        }
    }

    /** Age for speech: `12 seconds`, `1 minute`, `1 hour 5 minutes`. */
    fun spokenAge(ageMs: Long): String {
        val s = (ageMs.coerceAtLeast(0L) / 1000L)
        return when {
            s < 60 -> plural(s, "second")
            s < 3600 -> plural(s / 60, "minute")
            else -> {
                val m = (s % 3600) / 60
                if (m == 0L) plural(s / 3600, "hour")
                else "${plural(s / 3600, "hour")} ${plural(m, "minute")}"
            }
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
}

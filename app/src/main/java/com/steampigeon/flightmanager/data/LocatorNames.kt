package com.steampigeon.flightmanager.data

/**
 * When a locator's remembered display name should be re-persisted.
 *
 * The app remembers what each locator calls itself so the status panel can name
 * one that is **armed**, because an armed locator sends `TelemetryData` and
 * nothing else — no device name, and no receiver name either. Open the app with
 * the rocket already on the pad and armed and the live name is simply not there.
 */
object LocatorNames {

    /**
     * Should [incoming] replace the remembered name [known]?
     *
     * Two rules, both of which are silent when broken:
     *
     * - **An empty name never overwrites a stored one.** `TelemetryData` has no
     *   name field, so empty means "not carried on this message", never "renamed
     *   to nothing". Persisting it would erase the name at the exact moment the
     *   remembered one is the only one left.
     * - **Only when it changes.** `PreLaunchData` arrives at 1 Hz, so writing an
     *   unchanged name persists it 60 times a minute for the whole time the app
     *   is open.
     *
     * [known] is null when nothing is stored for this locator yet.
     */
    fun isNewName(known: String?, incoming: String): Boolean =
        incoming.isNotEmpty() && known != incoming
}

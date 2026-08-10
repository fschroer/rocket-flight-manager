package com.steampigeon.flightmanager

import android.util.Log

/**
 * Debug logging that does not ship.
 *
 * Every `Log.d` in this app used to reach logcat on a release build. Nothing
 * stripped them: `isMinifyEnabled` is false, so the usual
 * `-assumenosideeffects` rule never ran, and there were no `BuildConfig.DEBUG`
 * guards anywhere. A released build narrated its whole BLE lifecycle to anyone
 * with a cable — including, on every single connect, a full dump of the
 * peripheral's GATT table.
 *
 * **Severity, not source, decides what ships.** `d` is developer narration and
 * is compiled out. `Log.w` and `Log.e` are deliberately NOT wrapped and keep
 * going straight to `android.util.Log`: they describe conditions someone would
 * want to see in a field report, they are low volume by nature, and routing them
 * through a facade would invite the same blanket treatment that made the debug
 * logs a problem.
 *
 * The message argument is built even in release, since Kotlin evaluates it
 * before the call. That is deliberate — a plain `String` parameter keeps every
 * call site a one-word change from `Log.d`, and these paths are connection
 * events and bounded transfers rather than anything hot. Where a message is
 * genuinely expensive to build, guard the whole block with [enabled] instead;
 * the GATT table dump does exactly that.
 */
object SpLog {
    /** True on debug builds only. Guard expensive log-only work with this. */
    val enabled: Boolean = BuildConfig.DEBUG

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }
}

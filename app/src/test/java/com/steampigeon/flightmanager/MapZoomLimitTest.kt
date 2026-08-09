package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.ui.MAP_ZOOM_LIMIT_DEFAULT
import com.steampigeon.flightmanager.ui.MAP_ZOOM_LIMIT_MAX
import com.steampigeon.flightmanager.ui.MAP_ZOOM_LIMIT_MIN
import com.steampigeon.flightmanager.ui.resolveMapMaxZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The closest zoom the live map will go to.
 *
 * A GPS-error control, not a tile-supply one. Auto-zoom frames a box containing
 * the phone and the rocket; within a few meters that box is mostly the two
 * receivers' combined error, so it changes size sharply from fix to fix and the
 * fitted zoom chases it — the map jumps zoom levels every second or so, exactly
 * while the user is walking the last stretch. Capping the closest zoom gives the
 * jitter nowhere to go.
 */
class MapZoomLimitTest {

    @Test
    fun theDefaultIsInsideTheOfferedRange() {
        // A default outside the slider's range would show as a thumb pinned to an
        // end that does not match the number printed above it.
        assertTrue(MAP_ZOOM_LIMIT_DEFAULT in MAP_ZOOM_LIMIT_MIN..MAP_ZOOM_LIMIT_MAX)
        assertEquals(MAP_ZOOM_LIMIT_DEFAULT, resolveMapMaxZoom(null))
    }

    @Test
    fun aStoredChoiceIsHonored() {
        for (z in MAP_ZOOM_LIMIT_MIN..MAP_ZOOM_LIMIT_MAX) {
            assertEquals(z, resolveMapMaxZoom(z))
        }
    }

    @Test
    fun aStoredZeroIsNotMistakenForUnset() {
        // Why the stored value is nullable rather than defaulted: proto3 reads 0
        // for an absent field, and folding the two together would resolve an
        // untouched install to the floor instead of the default.
        assertEquals(MAP_ZOOM_LIMIT_MIN, resolveMapMaxZoom(0))
        assertEquals(MAP_ZOOM_LIMIT_DEFAULT, resolveMapMaxZoom(null))
    }

    @Test
    fun valuesOutsideTheRangeAreClamped() {
        // A value stored by an older or newer build, or one carried across a
        // change to these constants, still has to resolve to something the slider
        // can show and the camera can use.
        assertEquals(MAP_ZOOM_LIMIT_MIN, resolveMapMaxZoom(-5))
        assertEquals(MAP_ZOOM_LIMIT_MIN, resolveMapMaxZoom(14))
        assertEquals(MAP_ZOOM_LIMIT_MAX, resolveMapMaxZoom(25))
    }

    @Test
    fun everyResolvedValueIsUsable() {
        for (stored in listOf(null, -5, 0, 10, 17, 18, 20, 22, 23, 99)) {
            val z = resolveMapMaxZoom(stored)
            assertTrue(
                "stored=$stored resolved to $z, outside the offered range",
                z in MAP_ZOOM_LIMIT_MIN..MAP_ZOOM_LIMIT_MAX,
            )
        }
    }
}

package com.steampigeon.flightmanager

import com.steampigeon.flightmanager.data.LocatorNames.isNewName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a locator's remembered display name is re-persisted.
 *
 * The name exists to fill the status panel for an ARMED locator, which sends
 * TelemetryData and therefore no name at all. Both rules here fail silently: one
 * erases the name at the moment it is the only one left, the other writes to the
 * DataStore at the 1 Hz broadcast rate for as long as the app is open.
 */
class LocatorNamesTest {

    private companion object {
        const val NAME = "Pigeon 1"
    }

    @Test
    fun aFirstNameIsStored() {
        assertTrue(isNewName(known = null, incoming = NAME))
    }

    @Test
    fun aRenameIsStored() {
        assertTrue(isNewName(known = "Pigeon 1", incoming = "Pigeon 2"))
    }

    @Test
    fun anUnchangedNameIsNotRewritten() {
        // PreLaunchData repeats the name at 1 Hz. Persisting each one is churn.
        assertFalse(isNewName(known = NAME, incoming = NAME))
    }

    @Test
    fun anEmptyNameNeverOverwritesAStoredOne() {
        // TelemetryData has no name field, so empty means "not carried here" —
        // never "renamed to nothing". This is the armed case, which is exactly
        // when the remembered name is the only one there is.
        assertFalse(isNewName(known = NAME, incoming = ""))
    }

    @Test
    fun anEmptyNameIsNotStoredForAnUnknownLocatorEither() {
        assertFalse(isNewName(known = null, incoming = ""))
    }

    @Test
    fun aNameDifferingOnlyInCaseOrSpaceIsARename() {
        // The locator's name is whatever was typed into it; nothing normalises it,
        // so the app must not decide two spellings are the same name.
        assertTrue(isNewName(known = "Pigeon 1", incoming = "pigeon 1"))
        assertTrue(isNewName(known = "Pigeon 1", incoming = "Pigeon 1 "))
    }
}

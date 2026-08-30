package com.wirekey.audio

import com.wirekey.util.HidKeyCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the exact bug class AcousticTypingEngine.onCharacterDown hit:
 * every character HidReportBuilder can actually send a HID report for must also resolve
 * to a physical key name AcousticTypingEngine can look a sound up by, or that character
 * silently plays no click even though the keystroke itself is sent correctly.
 */
class AcousticSoundMappingTest {

    @Test
    fun everyPhysicalKeyHasAnExplicitFingerAssignment() {
        // KeyboardAcoustics.fingerFor() falls back to a neutral right-index stroke rather
        // than crashing, so an unmapped key is inaudible only as "wrong gain, wrong side"
        // — worth asserting rather than discovering by ear.
        val unmapped = (HidKeyCodes.physicalKeyName.values.toSet() + "ShiftLeft")
            .filter { KeyboardAcoustics.fingerForOrNull(it) == null }

        assertTrue("Physical keys with no finger assignment: $unmapped", unmapped.isEmpty())
    }

    @Test
    fun everyCharMapKeyCodeHasAPhysicalKeyName() {
        val missing = HidKeyCodes.charMap.entries
            .filter { (_, mapping) -> HidKeyCodes.physicalKeyName[mapping.first] == null }
            .map { (char, mapping) -> "'$char' (keyCode=0x${mapping.first.toString(16)})" }

        assertTrue("Characters with no physicalKeyName entry (would play no click): $missing", missing.isEmpty())
    }

    @Test
    fun physicalKeyNamesAreNonBlank() {
        for ((keyCode, name) in HidKeyCodes.physicalKeyName) {
            assertFalse("physicalKeyName for keyCode 0x${keyCode.toString(16)} is blank", name.isBlank())
        }
    }

    @Test
    fun soundPackCatalogIdsAreUniqueAndNonBlank() {
        val ids = SoundPackCatalog.ALL_PACKS.map { it.id }
        assertEquals("Duplicate sound pack ids found: $ids", ids.size, ids.toSet().size)
        for (pack in SoundPackCatalog.ALL_PACKS) {
            assertFalse("Sound pack id is blank", pack.id.isBlank())
            assertFalse("Sound pack '${pack.id}' has a blank displayName", pack.displayName.isBlank())
        }
    }

    @Test
    fun defaultPackIdIsAnActualCatalogEntry() {
        val match = SoundPackCatalog.ALL_PACKS.firstOrNull { it.id == SoundPackCatalog.DEFAULT_PACK_ID }
        assertNotNull(
            "DEFAULT_PACK_ID '${SoundPackCatalog.DEFAULT_PACK_ID}' is not in ALL_PACKS",
            match
        )
    }
}

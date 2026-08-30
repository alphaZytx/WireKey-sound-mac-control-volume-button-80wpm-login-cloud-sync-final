package com.wirekey.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlSettingsTest {

    @Test
    fun `window is clamped to the supported range`() {
        assertEquals(RemoteControlSettings.MIN_WINDOW_MS, RemoteControlSettings.coerceWindow(0))
        assertEquals(RemoteControlSettings.MIN_WINDOW_MS, RemoteControlSettings.coerceWindow(-500))
        assertEquals(RemoteControlSettings.MAX_WINDOW_MS, RemoteControlSettings.coerceWindow(99_999))
        assertEquals(900, RemoteControlSettings.coerceWindow(900))
    }

    @Test
    fun `feature ships disabled so nothing changes until the user opts in`() {
        assertFalse(RemoteControlSettings.DEFAULT.enabled)
        assertEquals(LockKey.CAPS_LOCK, RemoteControlSettings.DEFAULT.triggerKey)
        assertTrue(RemoteControlSettings.DEFAULT.hapticsEnabled)
    }

    @Test
    fun `lock key ids round-trip and unknown ids fall back to the default`() {
        LockKey.values().forEach { key ->
            assertEquals(key, LockKey.fromId(key.id))
        }
        assertEquals(LockKey.DEFAULT, LockKey.fromId(null))
        assertEquals(LockKey.DEFAULT, LockKey.fromId(""))
        assertEquals(LockKey.DEFAULT, LockKey.fromId("something-removed-in-a-later-version"))
    }

    /** Masks must match the USB HID LED usage page the descriptor declares. */
    @Test
    fun `lock key masks match the HID LED usage page`() {
        assertEquals(0x01, LockKey.NUM_LOCK.mask)
        assertEquals(0x02, LockKey.CAPS_LOCK.mask)
        assertEquals(0x04, LockKey.SCROLL_LOCK.mask)
    }

    /** Only Caps Lock changes what the host types, so only it needs the pause guard. */
    @Test
    fun `only caps lock is flagged as case-affecting`() {
        assertTrue(LockKey.CAPS_LOCK.affectsCase)
        assertFalse(LockKey.NUM_LOCK.affectsCase)
        assertFalse(LockKey.SCROLL_LOCK.affectsCase)
    }
}

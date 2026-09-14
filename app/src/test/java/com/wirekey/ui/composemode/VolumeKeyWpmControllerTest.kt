package com.wirekey.ui.composemode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.wirekey.data.SettingsRepository
import org.junit.Test

class VolumeKeyWpmControllerTest {

    @Test
    fun `ignores keys when no screen is registered`() {
        val controller = VolumeKeyWpmController()

        assertFalse(controller.isActive)
        // False is what makes the activity fall through to normal system volume handling.
        assertFalse(controller.adjust(VolumeKeyWpmController.STEP_WPM))
    }

    @Test
    fun `routes the step to the registered screen`() {
        val controller = VolumeKeyWpmController()
        val deltas = mutableListOf<Int>()
        controller.register { deltas.add(it) }

        assertTrue(controller.isActive)
        assertTrue(controller.adjust(5))
        assertTrue(controller.adjust(-5))
        assertEquals(listOf(5, -5), deltas)
    }

    @Test
    fun `releases the keys once the screen is disposed`() {
        val controller = VolumeKeyWpmController()
        val deltas = mutableListOf<Int>()
        val registration = controller.register { deltas.add(it) }

        registration.unregister()

        assertFalse(controller.isActive)
        assertFalse(controller.adjust(5))
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `a stale registration cannot steal the keys from a newer screen`() {
        // Compose gives no ordering guarantee between a leaving screen's disposal and its
        // replacement's registration, so the late unregister must be a no-op.
        val controller = VolumeKeyWpmController()
        val old = controller.register { }
        val newDeltas = mutableListOf<Int>()
        controller.register { newDeltas.add(it) }

        old.unregister()

        assertTrue(controller.isActive)
        assertTrue(controller.adjust(5))
        assertEquals(listOf(5), newDeltas)
    }

    @Test
    fun `each press moves the speed by five`() {
        assertEquals(65, VolumeKeyWpmController.nextWpm(60, VolumeKeyWpmController.STEP_WPM))
        assertEquals(55, VolumeKeyWpmController.nextWpm(60, -VolumeKeyWpmController.STEP_WPM))
        assertEquals(5, VolumeKeyWpmController.STEP_WPM)
    }

    @Test
    fun `held key ramps without ever escaping the range`() {
        // Auto-repeat delivers a burst of ACTION_DOWNs; the speed must saturate, not run away.
        var wpm = 80
        repeat(200) { wpm = VolumeKeyWpmController.nextWpm(wpm, VolumeKeyWpmController.STEP_WPM) }
        assertEquals(VolumeKeyWpmController.MAX_WPM, wpm)

        repeat(500) { wpm = VolumeKeyWpmController.nextWpm(wpm, -VolumeKeyWpmController.STEP_WPM) }
        assertEquals(VolumeKeyWpmController.MIN_WPM, wpm)
    }

    @Test
    fun `up then down returns to the starting speed`() {
        var wpm = 80
        repeat(6) { wpm = VolumeKeyWpmController.nextWpm(wpm, VolumeKeyWpmController.STEP_WPM) }
        repeat(6) { wpm = VolumeKeyWpmController.nextWpm(wpm, -VolumeKeyWpmController.STEP_WPM) }
        assertEquals(80, wpm)
    }

    @Test
    fun `saturating at a bound does not bank extra presses`() {
        // Pressing up past the top then once down must drop by exactly one step, rather than
        // working off invisible credit built up while held against the ceiling.
        var wpm = VolumeKeyWpmController.MAX_WPM
        repeat(10) { wpm = VolumeKeyWpmController.nextWpm(wpm, VolumeKeyWpmController.STEP_WPM) }
        wpm = VolumeKeyWpmController.nextWpm(wpm, -VolumeKeyWpmController.STEP_WPM)
        assertEquals(VolumeKeyWpmController.MAX_WPM - VolumeKeyWpmController.STEP_WPM, wpm)
    }

    @Test
    fun `the default speed sits inside the adjustable range`() {
        // A default outside the range would be clamped on the very first press, making the
        // speed jump instead of step.
        assertTrue(SettingsRepository.DEFAULT_TARGET_WPM >= VolumeKeyWpmController.MIN_WPM)
        assertTrue(SettingsRepository.DEFAULT_TARGET_WPM <= VolumeKeyWpmController.MAX_WPM)
    }

    @Test
    fun `re-registering replaces the previous screen rather than stacking`() {
        val controller = VolumeKeyWpmController()
        val first = mutableListOf<Int>()
        val second = mutableListOf<Int>()
        controller.register { first.add(it) }
        controller.register { second.add(it) }

        controller.adjust(5)

        assertTrue(first.isEmpty())
        assertEquals(listOf(5), second)
    }

    @Test
    fun `clamps to the speed slider's range`() {
        assertEquals(
            VolumeKeyWpmController.MAX_WPM,
            VolumeKeyWpmController.nextWpm(VolumeKeyWpmController.MAX_WPM, 5)
        )
        assertEquals(
            VolumeKeyWpmController.MIN_WPM,
            VolumeKeyWpmController.nextWpm(VolumeKeyWpmController.MIN_WPM, -5)
        )
        // A press near a bound lands exactly on it rather than overshooting.
        assertEquals(VolumeKeyWpmController.MAX_WPM, VolumeKeyWpmController.nextWpm(248, 5))
        assertEquals(VolumeKeyWpmController.MIN_WPM, VolumeKeyWpmController.nextWpm(12, -5))
    }
}

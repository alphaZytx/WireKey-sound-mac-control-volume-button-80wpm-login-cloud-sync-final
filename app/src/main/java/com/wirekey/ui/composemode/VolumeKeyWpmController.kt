package com.wirekey.ui.composemode

import java.util.concurrent.atomic.AtomicReference

/**
 * Handle returned by [VolumeKeyWpmController.register].
 *
 * Identity-checked on the way out, for the same reason as StartHandlerRegistration: Compose
 * gives no ordering guarantee between a leaving screen's disposal and its replacement's
 * registration, so a late unregister must not clear a newer screen's slot.
 */
class VolumeKeyWpmRegistration internal constructor(
    private val controller: VolumeKeyWpmController,
    internal val onAdjust: (Int) -> Unit
) {
    fun unregister() = controller.unregister(this)
}

/**
 * Routes the phone's hardware volume keys to Compose Mode's typing-speed control.
 *
 * Registration is the whole scoping mechanism, and it is deliberately tied to the Compose Mode
 * screen being *composed* rather than to its ViewModel. A nav-scoped ViewModel outlives the
 * screen -- navigating to Settings keeps Compose Mode on the back stack -- so registering there
 * would swallow the volume keys on other screens too.
 *
 * Everywhere else the keys keep their normal behaviour: on other screens nothing is registered,
 * and while the app is backgrounded the activity never sees the events at all.
 */
class VolumeKeyWpmController {

    private val active = AtomicReference<VolumeKeyWpmRegistration?>(null)

    /** True only while a visible Compose Mode screen wants the volume keys. */
    val isActive: Boolean get() = active.get() != null

    fun register(onAdjust: (Int) -> Unit): VolumeKeyWpmRegistration {
        val registration = VolumeKeyWpmRegistration(this, onAdjust)
        active.set(registration)
        return registration
    }

    internal fun unregister(registration: VolumeKeyWpmRegistration) {
        active.compareAndSet(registration, null)
    }

    /**
     * Applies one volume-key step.
     *
     * @return true when Compose Mode consumed the key, meaning the caller must swallow the
     *   event and leave the system volume alone. False restores normal volume behaviour.
     */
    fun adjust(deltaWpm: Int): Boolean {
        val registration = active.get() ?: return false
        registration.onAdjust(deltaWpm)
        return true
    }

    companion object {
        /** One key press. Holding a volume key repeats, which ramps the speed. */
        const val STEP_WPM = 5

        // Matched to the Compose Mode speed slider's own valueRange so the two controls can
        // never disagree about what a legal speed is.
        const val MIN_WPM = 10
        const val MAX_WPM = 250

        fun nextWpm(currentWpm: Int, deltaWpm: Int): Int =
            (currentWpm + deltaWpm).coerceIn(MIN_WPM, MAX_WPM)
    }
}

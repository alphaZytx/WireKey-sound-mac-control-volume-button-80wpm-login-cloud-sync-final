package com.wirekey.remote

import com.wirekey.util.HidKeyCodes

/**
 * What the coordinator decided to do in response to a completed trigger gesture.
 *
 * The two IGNORED_* values are real outcomes, not error codes -- they are fed back
 * to the user as a distinct "rejected" haptic so a trigger never fails silently.
 */
enum class RemoteAction {
    START,
    PAUSE,
    RESUME,
    IGNORED_NOTHING_TO_SEND,
    IGNORED_NO_SCREEN
}

/**
 * The host lock key whose LED state we listen to.
 *
 * A Bluetooth HID *device* can never see the keys a host presses -- HID key data only
 * flows device -> host. The one thing a host pushes back down, unprompted and with no
 * software installed on it, is the LED output report that keeps a keyboard's
 * Caps/Num/Scroll-Lock lights in sync. That 5-bit report is the entire channel this
 * feature runs on, which is why the trigger has to be a lock key.
 *
 * [mask] indexes into that report's byte, per the USB HID LED usage page (and matching
 * the `05 08 19 01 29 05 91 02` block already present in the app's HID descriptor):
 * bit0 NumLock, bit1 CapsLock, bit2 ScrollLock, bit3 Compose, bit4 Kana.
 *
 * [affectsCase] flags the one key whose ON state changes what the host types. Only
 * Caps Lock does, and only that key needs the provisional-pause protection in
 * [LockKeyGestureDetector].
 *
 * [keyCode] is the HID usage the diagnostics self-test taps to make the host emit an LED
 * report on demand. Nothing else in the app ever sends a lock key.
 */
enum class LockKey(
    val id: String,
    val mask: Int,
    val displayName: String,
    val affectsCase: Boolean,
    val keyCode: Int
) {
    CAPS_LOCK("capslock", 0x02, "Caps Lock", true, HidKeyCodes.KEY_CAPSLOCK),
    NUM_LOCK("numlock", 0x01, "Num Lock", false, HidKeyCodes.KEY_NUMLOCK),
    SCROLL_LOCK("scrolllock", 0x04, "Scroll Lock", false, HidKeyCodes.KEY_SCROLLLOCK);

    companion object {
        val DEFAULT = CAPS_LOCK

        /** Tolerant lookup -- an unknown or absent stored id falls back to [DEFAULT]. */
        fun fromId(id: String?): LockKey = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * What a single inbound LED report -- or the expiry of a gesture window -- means.
 *
 * Produced by [LockKeyGestureDetector], consumed by RemoteControlCoordinator.
 */
sealed interface GestureEvent {

    /** Nothing to act on. */
    data object None : GestureEvent

    /**
     * The first edge of a possible gesture landed while a send was running with a
     * case-affecting trigger key. The send must be paused *now*, before the gesture is
     * even resolved -- see [LockKeyGestureDetector]'s class doc for why.
     */
    data object ProvisionalPause : GestureEvent

    /**
     * The gesture completed.
     *
     * [afterProvisionalPause] matters: when true, the send was already paused on the
     * first edge, so the state machine must NOT be re-run (it would read
     * `isPaused == true` and resolve to RESUME, the exact opposite of what the user
     * asked for). The completed double-tap merely confirms PAUSE was the intent.
     */
    data class Trigger(val afterProvisionalPause: Boolean) : GestureEvent

    /**
     * The window closed on a lone edge that left a case-affecting lock key ON. This was
     * a genuine Caps Lock press, not a gesture. Resuming would type the rest of the
     * message in the wrong case, so stay paused and tell the user.
     */
    data object StuckLockWarning : GestureEvent

    /**
     * The window closed on a lone edge that left the key OFF -- a genuine press that
     * turned Caps Lock *off*. Nothing can be corrupted from here, so undo the
     * provisional pause and carry on.
     */
    data object ReleaseProvisionalPause : GestureEvent
}

/** A short, user-facing description of the last thing the remote control did. */
data class RemoteNotice(val message: String, val isWarning: Boolean = false)

/**
 * One line of host->device HID traffic, retained for the on-device diagnostics panel.
 *
 * This exists so the go/no-go question -- "does a Mac actually push LED reports down to
 * a Bluetooth HID keyboard?" -- can be answered from the app's own Settings screen
 * rather than from `adb logcat`.
 *
 * Entries with [isHostActivity] set are not LED reports at all but the other host->device
 * callbacks (GET_REPORT, protocol changes, the link coming up). They are logged because
 * an empty panel is otherwise unreadable: "the Mac never talks to us" and "the Mac talks
 * but never sends LED state" look identical, and they are different problems.
 */
data class LedReportLog(
    val wallClockMs: Long,
    val rawHex: String,
    val ledByte: Int?,
    val source: String,
    val triggerBitOn: Boolean?,
    val wasEdge: Boolean,
    /** Milliseconds since the previous edge of the trigger bit; null if unknown. */
    val gapSincePreviousEdgeMs: Long?,
    val isHostActivity: Boolean = false
)

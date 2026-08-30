package com.wirekey.remote

/**
 * Immutable snapshot of the remote-control preferences, mirroring how
 * `CadenceSettings` is threaded through the typing pipeline.
 *
 * The coordinator keeps one of these in a @Volatile field and refreshes it from
 * DataStore, so handling an inbound LED report never has to do suspending work.
 */
data class RemoteControlSettings(
    val enabled: Boolean,
    val triggerKey: LockKey,
    val gestureWindowMs: Int,
    val hapticsEnabled: Boolean
) {
    companion object {
        /**
         * macOS applies a deliberate activation delay to Caps Lock (you must hold it
         * briefly) specifically to stop accidental toggling. That makes a "quick"
         * double-tap take far longer than a normal double-click -- so this default is
         * deliberately generous, and it is user-adjustable because the real figure
         * varies by machine and by how hard you tap.
         */
        const val DEFAULT_WINDOW_MS = 1200
        const val MIN_WINDOW_MS = 300
        const val MAX_WINDOW_MS = 3000

        val DEFAULT = RemoteControlSettings(
            enabled = false,
            triggerKey = LockKey.DEFAULT,
            gestureWindowMs = DEFAULT_WINDOW_MS,
            hapticsEnabled = true
        )

        fun coerceWindow(ms: Int): Int = ms.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
    }
}

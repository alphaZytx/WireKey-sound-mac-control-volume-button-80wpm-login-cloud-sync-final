package com.wirekey.remote

/**
 * Turns a stream of raw HID LED output reports into "the user tapped the lock key twice".
 *
 * ## Why a double tap
 *
 * A *single* tap is deliberately ignored, which is what keeps the trigger from hampering
 * anything else: pressing Caps Lock on the Mac goes on behaving exactly as it always has.
 * A double tap is also self-cancelling -- the key ends in the state it started in -- so
 * using the trigger leaves nothing changed on the host.
 *
 * ## The case-corruption problem, and the provisional pause
 *
 * Between the two taps, Caps Lock is genuinely ON. If the user is double-tapping in order
 * to *pause* a send, WireKey is still typing during that window, and the host applies Caps
 * Lock to those characters -- silently corrupting several letters mid-message. macOS's own
 * Caps Lock activation delay makes that window longer, not shorter.
 *
 * So when the first edge lands mid-send with a case-affecting key, this detector emits
 * [GestureEvent.ProvisionalPause] *immediately*, before it knows whether a gesture is even
 * happening. Nothing is typed while the key's state is in flux. Then:
 *
 *  - second edge arrives in time -> [GestureEvent.Trigger] with `afterProvisionalPause`,
 *    and the caller must not re-run the state machine (the send is already paused, so it
 *    would resolve to RESUME -- the opposite of the user's intent);
 *  - window expires with the key ON -> [GestureEvent.StuckLockWarning]: a genuine Caps
 *    Lock press. Stay paused rather than type the remainder in the wrong case;
 *  - window expires with the key OFF -> [GestureEvent.ReleaseProvisionalPause]: a genuine
 *    press that turned it *off*, nothing can be corrupted, so carry on.
 *
 * A pleasant side effect is that pausing lands on the *first* tap, so it feels faster than
 * the gesture window suggests.
 *
 * ## Threading
 *
 * Not thread-safe by design. The coordinator drives it exclusively from
 * `Dispatchers.Main.immediate`, so every call is already serialized.
 *
 * @param clock monotonic milliseconds (`SystemClock.elapsedRealtime` in production,
 *   a fake in tests). Must never go backwards.
 */
class LockKeyGestureDetector(
    private val clock: () -> Long,
    triggerKey: LockKey = LockKey.DEFAULT,
    windowMs: Int = RemoteControlSettings.DEFAULT_WINDOW_MS
) {

    var triggerKey: LockKey = triggerKey
        private set

    var windowMs: Int = RemoteControlSettings.coerceWindow(windowMs)
        private set

    /**
     * Last known state of the trigger bit. Null means "not yet known", which now only
     * covers the window before the link has ever come up -- see [onLinkUp].
     */
    private var lastBitState: Boolean? = null

    private var firstEdgeAt = 0L
    private var provisionalPauseIssued = false
    private var lockoutUntil = 0L

    private var awaitingSecondEdge = false

    /**
     * True while a first edge is banked and its partner could still arrive. The caller
     * uses this to schedule (or cancel) the window-expiry callback.
     */
    val isAwaitingSecondEdge: Boolean get() = awaitingSecondEdge

    /** Applies new preferences. Changing the trigger key invalidates all tracked state. */
    fun configure(key: LockKey, window: Int) {
        if (key != triggerKey) reset()
        triggerKey = key
        windowMs = RemoteControlSettings.coerceWindow(window)
    }

    /**
     * Abandons any half-finished gesture while keeping the tracked LED state.
     *
     * Reports are fed in even while the feature is switched off, purely to keep
     * [lastBitState] current. That would otherwise leave a banked first edge behind: tap
     * the key once with the feature off, switch it on, tap once more inside the window,
     * and a single deliberate tap would complete a gesture it never started. Clearing the
     * pending half on every enable/disable flip closes that, and keeping [lastBitState]
     * means the very next double tap still works in full.
     */
    fun clearPendingGesture() {
        awaitingSecondEdge = false
        provisionalPauseIssued = false
        firstEdgeAt = 0L
        lockoutUntil = 0L
    }

    /**
     * Drops all tracked state. Called when the HID link goes away, so a stale bit state
     * from a previous session can never be mistaken for an edge on reconnect.
     */
    fun reset() {
        lastBitState = null
        clearPendingGesture()
    }

    /**
     * Called when the HID link comes up, to seed the tracked state as "lock keys off".
     *
     * This deliberately assumes a state rather than waiting to be told one. The original
     * design treated the first report of a session as the host announcing its current LED
     * state, and discarded it -- but macOS sends no such announcement. Measured on a real
     * link, the first report of a session arrives only when a key is actually pressed,
     * about 150 ms in. Discarding it therefore ate the first genuine press every session,
     * which cost the user their whole first double tap with no feedback at all.
     *
     * Seeding is safe in a way that waiting is not: a *single* edge can never fire a
     * command, so even if this assumption is wrong -- the rare case of connecting with Caps
     * Lock already on -- the worst outcome is one banked edge that expires harmlessly.
     */
    fun onLinkUp() {
        lastBitState = false
        clearPendingGesture()
    }

    /**
     * Feeds one raw output report in.
     *
     * @param guardCase true when a send is actively running (not already paused) and the
     *   trigger key affects character case -- i.e. when the provisional pause is needed.
     */
    fun onReport(data: ByteArray?, guardCase: Boolean): GestureEvent {
        val led = extractLedByte(data) ?: return GestureEvent.None
        val bitOn = (led and triggerKey.mask) != 0
        val now = clock()

        val previous = lastBitState
        // Track state unconditionally -- including during lockout and on the initial
        // sync -- so the detector can never fall out of step with the host.
        lastBitState = bitOn

        // Only reachable before the link has ever come up; [onLinkUp] seeds a real state.
        if (previous == null) return GestureEvent.None

        // Same state means no edge. This is also what makes duplicate delivery harmless:
        // a host that sends the same report on both the control and interrupt channels
        // produces one edge and one no-op, not two edges.
        if (previous == bitOn) return GestureEvent.None

        // Post-trigger lockout, so a triple tap fires exactly once.
        if (now < lockoutUntil) return GestureEvent.None

        if (awaitingSecondEdge && now - firstEdgeAt <= windowMs) {
            val after = provisionalPauseIssued
            awaitingSecondEdge = false
            provisionalPauseIssued = false
            lockoutUntil = now + LOCKOUT_MS
            return GestureEvent.Trigger(afterProvisionalPause = after)
        }

        // Either a fresh first edge, or a stale one whose window already closed -- in both
        // cases this edge starts a new gesture.
        awaitingSecondEdge = true
        firstEdgeAt = now
        provisionalPauseIssued = guardCase
        return if (guardCase) GestureEvent.ProvisionalPause else GestureEvent.None
    }

    /**
     * Called by the coordinator once the gesture window should have closed. Safe to call
     * spuriously: it verifies the window really has elapsed, so an early or duplicated
     * timer cannot cut a gesture short.
     */
    fun onWindowElapsed(): GestureEvent {
        if (!awaitingSecondEdge) return GestureEvent.None
        if (clock() - firstEdgeAt < windowMs) return GestureEvent.None

        awaitingSecondEdge = false
        val hadProvisionalPause = provisionalPauseIssued
        provisionalPauseIssued = false

        if (!hadProvisionalPause) return GestureEvent.None

        return if (lastBitState == true) {
            GestureEvent.StuckLockWarning
        } else {
            GestureEvent.ReleaseProvisionalPause
        }
    }

    companion object {
        /** Swallows further edges after a trigger, so a triple tap fires once. */
        const val LOCKOUT_MS = 700L

        /**
         * Pulls the LED bitmask byte out of a raw output report.
         *
         * The app's HID descriptor declares no report IDs and exactly one byte of output
         * (5 LED bits + 3 bits of padding), so a well-behaved host sends a single byte.
         *
         * A longer payload is ambiguous, and guessing wrong fails silently: it reads a byte
         * that never changes, so the trigger key looks permanently off and no gesture can
         * ever complete. Both Android callbacks hand the report ID over as a separate
         * argument, so byte 0 is normally the LED byte and anything after it is padding --
         * except on a stack that leaves the ID in the payload anyway, where byte 0 is a
         * zero prefix (the only ID this descriptor can produce) and the bits sit behind it.
         * So byte 0 wins unless it cannot be the answer: bits set outside the descriptor's
         * five LED usages mean it is a prefix, and a zero byte 0 is read through to byte 1,
         * which agrees with it whenever byte 1 really is padding. Anything unparseable
         * returns null rather than throwing -- this runs on a Bluetooth callback thread and
         * must never crash it.
         */
        fun extractLedByte(data: ByteArray?): Int? = when {
            data == null || data.isEmpty() -> null
            data.size == 1 -> data[0].toInt() and 0xFF
            else -> {
                val first = data[0].toInt() and 0xFF
                val second = data[1].toInt() and 0xFF
                when {
                    // Byte 0 has bits this descriptor's 5 LED usages cannot produce, so it
                    // is not the LED byte at all -- it is a prefix of some kind.
                    first > LED_BITS_MAX -> second
                    // A zero byte 0 is equally consistent with "all LEDs off, padding
                    // follows" and "report-ID 0 prefix". Only the second reading can carry
                    // information, and it agrees with the first whenever byte 1 is empty.
                    first == 0x00 -> second
                    else -> first
                }
            }
        }

        /** Largest value the descriptor's 5 LED bits can hold. */
        private const val LED_BITS_MAX = 0x1F
    }
}

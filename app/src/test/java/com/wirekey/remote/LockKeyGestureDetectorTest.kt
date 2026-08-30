package com.wirekey.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector is deliberately pure with an injected clock, so every timing path here
 * runs instantly and deterministically -- no device, no waiting.
 */
class LockKeyGestureDetectorTest {

    private var now = 1_000L
    private fun detector(
        key: LockKey = LockKey.CAPS_LOCK,
        window: Int = 1200
    ) = LockKeyGestureDetector(clock = { now }, triggerKey = key, windowMs = window)

    private fun capsOn() = byteArrayOf(0x02)
    private fun capsOff() = byteArrayOf(0x00)

    /** Establishes the initial state on a detector that has not seen the link come up. */
    private fun LockKeyGestureDetector.sync(data: ByteArray = capsOff()) {
        assertEquals(GestureEvent.None, onReport(data, guardCase = false))
    }

    // ── Edge basics ──────────────────────────────────────────────────────────

    @Test
    fun `before the link comes up the first report only establishes state`() {
        val d = detector()
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        assertFalse(d.isAwaitingSecondEdge)
    }

    // ── Link-up baseline ─────────────────────────────────────────────────────

    @Test
    fun `after link up the first press counts as an edge`() {
        // macOS announces no LED state on connect, so waiting to be told one silently
        // discarded the user's first genuine press of every session.
        val d = detector()
        d.onLinkUp()
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        assertTrue(d.isAwaitingSecondEdge)
    }

    @Test
    fun `the very first double tap of a session fires a trigger`() {
        val d = detector()
        d.onLinkUp()
        d.onReport(capsOn(), guardCase = false)
        now += 400
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOff(), guardCase = false)
        )
    }

    @Test
    fun `link up abandons a gesture left over from the previous session`() {
        val d = detector()
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        assertTrue(d.isAwaitingSecondEdge)

        d.onLinkUp()
        assertFalse(d.isAwaitingSecondEdge)
    }

    @Test
    fun `connecting with caps already on loses one gesture but fires nothing spurious`() {
        // The seeded baseline is wrong here, so the press that turns Caps Lock *off* looks
        // like no change at all and the double tap is lost. That is the price of seeding,
        // and it is bounded: the worst case is a banked edge that expires on its own. It
        // can never invent a command, which is what makes the trade worth taking.
        val d = detector(window = 1200)
        d.onLinkUp()

        // First press of the double tap: on -> off, indistinguishable from the baseline.
        assertEquals(GestureEvent.None, d.onReport(capsOff(), guardCase = false))
        assertFalse(d.isAwaitingSecondEdge)

        // Second press: off -> on. A real edge, but on its own it banks and goes nowhere.
        now += 400
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        assertTrue(d.isAwaitingSecondEdge)

        now += 1300
        assertEquals(GestureEvent.None, d.onWindowElapsed())
        assertFalse(d.isAwaitingSecondEdge)
    }

    @Test
    fun `a single tap fires nothing so normal caps lock use is unaffected`() {
        val d = detector()
        d.sync()
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        assertTrue(d.isAwaitingSecondEdge)
    }

    @Test
    fun `two edges inside the window fire one trigger`() {
        val d = detector()
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        now += 400
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOff(), guardCase = false)
        )
        assertFalse(d.isAwaitingSecondEdge)
    }

    @Test
    fun `a double tap works the same when caps lock started on`() {
        val d = detector()
        d.sync(capsOn())
        d.onReport(capsOff(), guardCase = false)
        now += 300
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOn(), guardCase = false)
        )
    }

    @Test
    fun `second edge outside the window starts a new gesture instead of firing`() {
        val d = detector(window = 1200)
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        now += 1500
        assertEquals(GestureEvent.None, d.onReport(capsOff(), guardCase = false))
        assertTrue(d.isAwaitingSecondEdge)
    }

    @Test
    fun `an edge exactly on the window boundary still counts`() {
        val d = detector(window = 1200)
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        now += 1200
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOff(), guardCase = false)
        )
    }

    // ── Duplicate delivery ───────────────────────────────────────────────────

    /**
     * Hosts may deliver the same report on both the control and interrupt channels.
     * Identical states are not edges, so this needs no special-casing -- but it must be
     * proven, because double-counting would turn one tap into a full trigger.
     */
    @Test
    fun `the same report delivered twice counts as one edge`() {
        val d = detector()
        d.sync()
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        now += 5
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        now += 5
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        assertTrue(d.isAwaitingSecondEdge)
    }

    @Test
    fun `a fully duplicated double tap still fires exactly once`() {
        val d = detector()
        d.sync()
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        now += 300
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOff(), guardCase = false)
        )
        assertEquals(GestureEvent.None, d.onReport(capsOff(), guardCase = false))
    }

    @Test
    fun `repeated identical reports do not extend the window`() {
        val d = detector(window = 1000)
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        now += 600
        d.onReport(capsOn(), guardCase = false) // duplicate, not an edge
        now += 600                              // 1200ms total since the real first edge
        assertEquals(GestureEvent.None, d.onReport(capsOff(), guardCase = false))
    }

    // ── Lockout ──────────────────────────────────────────────────────────────

    @Test
    fun `a triple tap fires exactly once`() {
        val d = detector()
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        now += 200
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOff(), guardCase = false)
        )
        now += 200
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        now += 200
        assertEquals(GestureEvent.None, d.onReport(capsOff(), guardCase = false))
    }

    @Test
    fun `state stays in sync through the lockout so the next gesture still works`() {
        val d = detector()
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        now += 200
        d.onReport(capsOff(), guardCase = false) // trigger, lockout starts

        now += 100
        d.onReport(capsOn(), guardCase = false)  // swallowed by lockout, but tracked

        now += LockKeyGestureDetector.LOCKOUT_MS
        assertEquals(GestureEvent.None, d.onReport(capsOff(), guardCase = false))
        now += 200
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOn(), guardCase = false)
        )
    }

    // ── Provisional pause: the case-corruption guard ─────────────────────────

    @Test
    fun `first edge mid-send pauses immediately, before the gesture is resolved`() {
        val d = detector()
        d.sync()
        assertEquals(GestureEvent.ProvisionalPause, d.onReport(capsOn(), guardCase = true))
    }

    @Test
    fun `completing the double tap keeps the send paused instead of resuming it`() {
        val d = detector()
        d.sync()
        assertEquals(GestureEvent.ProvisionalPause, d.onReport(capsOn(), guardCase = true))
        now += 400
        // afterProvisionalPause=true tells the coordinator NOT to re-run the resolver,
        // which would read isPaused==true and RESUME -- the opposite of the intent.
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = true),
            d.onReport(capsOff(), guardCase = true)
        )
    }

    @Test
    fun `a lone press that leaves caps lock on keeps the send paused and warns`() {
        val d = detector(window = 1200)
        d.sync()
        assertEquals(GestureEvent.ProvisionalPause, d.onReport(capsOn(), guardCase = true))
        now += 1300
        assertEquals(GestureEvent.StuckLockWarning, d.onWindowElapsed())
    }

    @Test
    fun `a lone press that leaves caps lock off releases the provisional pause`() {
        val d = detector(window = 1200)
        d.sync(capsOn())
        assertEquals(GestureEvent.ProvisionalPause, d.onReport(capsOff(), guardCase = true))
        now += 1300
        assertEquals(GestureEvent.ReleaseProvisionalPause, d.onWindowElapsed())
    }

    @Test
    fun `no provisional pause when idle, so start and resume are unaffected`() {
        val d = detector()
        d.sync()
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        now += 1300
        assertEquals(GestureEvent.None, d.onWindowElapsed())
    }

    // ── Window expiry robustness ─────────────────────────────────────────────

    @Test
    fun `an early expiry callback cannot cut a gesture short`() {
        val d = detector(window = 1200)
        d.sync()
        d.onReport(capsOn(), guardCase = true)
        now += 100
        assertEquals(GestureEvent.None, d.onWindowElapsed())
        assertTrue(d.isAwaitingSecondEdge)
        now += 300
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = true),
            d.onReport(capsOff(), guardCase = true)
        )
    }

    @Test
    fun `expiry is idempotent`() {
        val d = detector(window = 1200)
        d.sync()
        d.onReport(capsOn(), guardCase = true)
        now += 1300
        assertEquals(GestureEvent.StuckLockWarning, d.onWindowElapsed())
        assertEquals(GestureEvent.None, d.onWindowElapsed())
        assertEquals(GestureEvent.None, d.onWindowElapsed())
    }

    @Test
    fun `expiry with no pending gesture does nothing`() {
        val d = detector()
        assertEquals(GestureEvent.None, d.onWindowElapsed())
        d.sync()
        assertEquals(GestureEvent.None, d.onWindowElapsed())
    }

    // ── Payload parsing ──────────────────────────────────────────────────────

    @Test
    fun `single byte payloads are read directly`() {
        assertEquals(0x02, LockKeyGestureDetector.extractLedByte(byteArrayOf(0x02)))
        assertEquals(0x00, LockKeyGestureDetector.extractLedByte(byteArrayOf(0x00)))
        assertEquals(0xFF, LockKeyGestureDetector.extractLedByte(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `two byte payloads skip a zero report-id prefix`() {
        assertEquals(0x02, LockKeyGestureDetector.extractLedByte(byteArrayOf(0x00, 0x02)))
        assertEquals(0x01, LockKeyGestureDetector.extractLedByte(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun `two byte payloads keep a leading LED byte followed by padding`() {
        // The failure this guards against is silent: reading the padding byte instead
        // leaves the trigger bit permanently off, so no gesture can ever complete.
        assertEquals(0x02, LockKeyGestureDetector.extractLedByte(byteArrayOf(0x02, 0x00)))
        assertEquals(0x01, LockKeyGestureDetector.extractLedByte(byteArrayOf(0x01, 0x04)))
    }

    @Test
    fun `a leading byte too large to be an LED bitmask is treated as a prefix`() {
        assertEquals(0x02, LockKeyGestureDetector.extractLedByte(byteArrayOf(0x00, 0x02, 0x00)))
        assertEquals(0x02, LockKeyGestureDetector.extractLedByte(byteArrayOf(0xA1.toByte(), 0x02)))
    }

    @Test
    fun `malformed payloads are ignored rather than throwing`() {
        assertNull(LockKeyGestureDetector.extractLedByte(null))
        assertNull(LockKeyGestureDetector.extractLedByte(byteArrayOf()))

        val d = detector()
        d.sync()
        assertEquals(GestureEvent.None, d.onReport(null, guardCase = true))
        assertEquals(GestureEvent.None, d.onReport(byteArrayOf(), guardCase = true))
        assertFalse(d.isAwaitingSecondEdge)
    }

    @Test
    fun `a prefixed payload drives the same gesture as a bare one`() {
        val d = detector()
        d.sync(byteArrayOf(0x00, 0x00))
        d.onReport(byteArrayOf(0x00, 0x02), guardCase = false)
        now += 300
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(byteArrayOf(0x00, 0x00), guardCase = false)
        )
    }

    // ── Trigger key selection ────────────────────────────────────────────────

    @Test
    fun `other led bits changing never fire the caps lock trigger`() {
        val d = detector(key = LockKey.CAPS_LOCK)
        d.sync(byteArrayOf(0x00))
        // Num Lock and Scroll Lock flipping, Caps Lock untouched.
        assertEquals(GestureEvent.None, d.onReport(byteArrayOf(0x01), guardCase = true))
        assertEquals(GestureEvent.None, d.onReport(byteArrayOf(0x05), guardCase = true))
        assertEquals(GestureEvent.None, d.onReport(byteArrayOf(0x00), guardCase = true))
        assertFalse(d.isAwaitingSecondEdge)
    }

    @Test
    fun `caps lock is still detected when other led bits are set alongside it`() {
        val d = detector(key = LockKey.CAPS_LOCK)
        d.sync(byteArrayOf(0x01))            // num lock on, caps off
        d.onReport(byteArrayOf(0x03), guardCase = false)  // caps on, num still on
        now += 300
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(byteArrayOf(0x01), guardCase = false)
        )
    }

    @Test
    fun `a non-case trigger key never asks for a provisional pause`() {
        val d = detector(key = LockKey.NUM_LOCK)
        d.sync(byteArrayOf(0x00))
        // guardCase is computed from key.affectsCase upstream, so it is false here.
        assertEquals(GestureEvent.None, d.onReport(byteArrayOf(0x01), guardCase = false))
        now += 300
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(byteArrayOf(0x00), guardCase = false)
        )
    }

    // ── Reconfiguration and reset ────────────────────────────────────────────

    @Test
    fun `switching trigger key drops stale state`() {
        val d = detector(key = LockKey.CAPS_LOCK)
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        assertTrue(d.isAwaitingSecondEdge)

        d.configure(LockKey.NUM_LOCK, 1200)
        assertFalse(d.isAwaitingSecondEdge)
        // Post-switch, the next report is a fresh state sync rather than an edge.
        assertEquals(GestureEvent.None, d.onReport(byteArrayOf(0x01), guardCase = false))
    }

    @Test
    fun `changing only the window keeps the pending gesture alive`() {
        val d = detector(window = 1200)
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        d.configure(LockKey.CAPS_LOCK, 800)
        assertTrue(d.isAwaitingSecondEdge)
        assertEquals(800, d.windowMs)
        now += 300
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOff(), guardCase = false)
        )
    }

    @Test
    fun `configure clamps the window`() {
        val d = detector()
        d.configure(LockKey.CAPS_LOCK, 10)
        assertEquals(RemoteControlSettings.MIN_WINDOW_MS, d.windowMs)
        d.configure(LockKey.CAPS_LOCK, 100_000)
        assertEquals(RemoteControlSettings.MAX_WINDOW_MS, d.windowMs)
    }

    /**
     * A reconnect makes the host resend its current LED state. Without the reset that
     * resend could differ from the remembered value and read as a phantom edge.
     */
    @Test
    fun `reset makes the next report a state sync again`() {
        val d = detector()
        d.sync(capsOff())
        d.reset()
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = true))
        assertFalse(d.isAwaitingSecondEdge)
    }

    @Test
    fun `reset clears a pending provisional pause`() {
        val d = detector()
        d.sync()
        assertEquals(GestureEvent.ProvisionalPause, d.onReport(capsOn(), guardCase = true))
        d.reset()
        now += 2000
        assertEquals(GestureEvent.None, d.onWindowElapsed())
    }

    // ── Sequences ────────────────────────────────────────────────────────────

    /**
     * Reports are fed in even while the feature is switched off. Without clearing the
     * pending half on the enable flip, one tap made while off could pair with one tap made
     * after switching on -- firing from two unrelated single presses.
     */
    @Test
    fun `clearPendingGesture stops a half gesture from spanning an enable flip`() {
        val d = detector()
        d.sync()
        d.onReport(capsOn(), guardCase = false)   // tap made while the feature was off
        assertTrue(d.isAwaitingSecondEdge)

        d.clearPendingGesture()                   // user switches the feature on
        assertFalse(d.isAwaitingSecondEdge)

        now += 300
        assertEquals(GestureEvent.None, d.onReport(capsOff(), guardCase = false))
        assertTrue(d.isAwaitingSecondEdge)
    }

    @Test
    fun `clearPendingGesture keeps led state so the next double tap works in full`() {
        val d = detector()
        d.sync(capsOff())
        d.clearPendingGesture()

        // lastBitState is still known, so this is a real edge rather than a state sync.
        d.onReport(capsOn(), guardCase = false)
        now += 300
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOff(), guardCase = false)
        )
    }

    @Test
    fun `clearPendingGesture drops a pending provisional pause`() {
        val d = detector()
        d.sync()
        assertEquals(GestureEvent.ProvisionalPause, d.onReport(capsOn(), guardCase = true))
        d.clearPendingGesture()
        now += 2000
        assertEquals(GestureEvent.None, d.onWindowElapsed())
    }

    @Test
    fun `clearPendingGesture lifts the post-trigger lockout`() {
        val d = detector()
        d.sync()
        d.onReport(capsOn(), guardCase = false)
        now += 200
        d.onReport(capsOff(), guardCase = false) // trigger, lockout starts
        d.clearPendingGesture()

        now += 50
        assertEquals(GestureEvent.None, d.onReport(capsOn(), guardCase = false))
        now += 200
        assertEquals(
            GestureEvent.Trigger(afterProvisionalPause = false),
            d.onReport(capsOff(), guardCase = false)
        )
    }

    @Test
    fun `three separate double taps in a row each fire once`() {
        val d = detector()
        d.sync()
        var triggers = 0
        repeat(3) {
            if (d.onReport(capsOn(), guardCase = false) is GestureEvent.Trigger) triggers++
            now += 250
            if (d.onReport(capsOff(), guardCase = false) is GestureEvent.Trigger) triggers++
            now += LockKeyGestureDetector.LOCKOUT_MS + 100
        }
        assertEquals(3, triggers)
    }
}

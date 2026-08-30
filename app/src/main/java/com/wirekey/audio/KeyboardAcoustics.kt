package com.wirekey.audio

/**
 * Which finger strikes each key on a touch-typed QWERTY layout, and what that implies
 * acoustically.
 *
 * Two things make a real keyboard sound like a person typing rather than a sampler firing:
 * strokes differ in force, and they come from two hands roughly 30 cm apart. Both are
 * cheap to model from the key alone -- fingers differ in strength quite consistently
 * (an index finger hits noticeably harder than a pinky), and left-hand keys reach a
 * listener from a different place than right-hand ones.
 *
 * Used by [AcousticTypingEngine] to set per-stroke gain and stereo balance. Keyed by the
 * physical key name from `HidKeyCodes.physicalKeyName` so it stays in step with the sound
 * lookup rather than duplicating a char table.
 */
internal enum class Finger(
    /** Relative strike force. Index fingers are the strongest and most practised. */
    val strength: Float,
    /** -1 = hard left, +1 = hard right. Reflects where the hand sits, not the key column. */
    val pan: Float,
) {
    LEFT_PINKY(0.74f, -0.34f),
    LEFT_RING(0.82f, -0.24f),
    LEFT_MIDDLE(0.93f, -0.15f),
    LEFT_INDEX(1.00f, -0.06f),
    RIGHT_INDEX(1.00f, 0.06f),
    RIGHT_MIDDLE(0.93f, 0.15f),
    RIGHT_RING(0.82f, 0.24f),
    RIGHT_PINKY(0.74f, 0.34f),

    /** Struck with the thumb, dead centre, and habitually harder than any letter. */
    THUMB(1.06f, 0.0f),
}

internal object KeyboardAcoustics {

    private val byKeyName: Map<String, Finger> = buildMap {
        fun assign(finger: Finger, vararg keys: String) = keys.forEach { put(it, finger) }

        assign(Finger.LEFT_PINKY, "KeyQ", "KeyA", "KeyZ", "Digit1", "Backquote", "Tab", "ShiftLeft")
        assign(Finger.LEFT_RING, "KeyW", "KeyS", "KeyX", "Digit2")
        assign(Finger.LEFT_MIDDLE, "KeyE", "KeyD", "KeyC", "Digit3")
        assign(Finger.LEFT_INDEX, "KeyR", "KeyF", "KeyV", "KeyT", "KeyG", "KeyB", "Digit4", "Digit5")
        assign(Finger.RIGHT_INDEX, "KeyY", "KeyH", "KeyN", "KeyU", "KeyJ", "KeyM", "Digit6", "Digit7")
        assign(Finger.RIGHT_MIDDLE, "KeyI", "KeyK", "Comma", "Digit8")
        assign(Finger.RIGHT_RING, "KeyO", "KeyL", "Period", "Digit9")
        assign(
            Finger.RIGHT_PINKY,
            "KeyP", "Semicolon", "Slash", "Quote", "Digit0",
            "Minus", "Equal", "BracketLeft", "BracketRight", "Backslash",
            "Enter", "Backspace",
        )
        assign(Finger.THUMB, "Space")
    }

    /** Falls back to a neutral, centred, average-force stroke for anything unmapped. */
    fun fingerFor(keyName: String): Finger = fingerForOrNull(keyName) ?: Finger.RIGHT_INDEX

    /** As [fingerFor] but without the fallback, so tests can assert full coverage. */
    fun fingerForOrNull(keyName: String): Finger? = byKeyName[keyName]
}

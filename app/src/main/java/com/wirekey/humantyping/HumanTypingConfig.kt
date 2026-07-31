package com.wirekey.humantyping

/**
 * Typing model configuration constants.
 * Ported from HumanTyping Python library (config.py).
 *
 * These constants control every aspect of the Markov-chain typing simulator:
 * timing distributions, error probabilities, speed modifiers, and fatigue.
 */
object HumanTypingConfig {

    // ── WPM defaults ──────────────────────────────────────────────────
    /** Default average typing speed (words per minute). */
    const val DEFAULT_WPM = 60

    /** Standard deviation applied to session WPM (Gaussian per-session variation). */
    const val WPM_STD = 10.0

    /** Average word length used to convert WPM → characters/second. */
    const val AVG_WORD_LENGTH = 5

    // ── Error probabilities ───────────────────────────────────────────
    /** Base probability of hitting a wrong neighbor key. */
    const val PROB_ERROR = 0.04

    /** Probability of a swap (anticipation) error, e.g. "the" → "hte". */
    const val PROB_SWAP_ERROR = 0.015

    /** Probability of a Shift-key sync error (e.g. "String" -> "STring"). */
    const val PROB_SHIFT_SYNC_ERROR = 0.04

    /** Probability of a cognitive muscle-memory typo (e.g. "i--" -> "i++"). */
    const val PROB_COGNITIVE_ERROR = 0.08

    /** Probability of noticing an error immediately (1 char past). */
    const val PROB_NOTICE_ERROR = 0.85

    /** Probability of noticing a drifted error (≥2 chars past). */
    const val DRIFT_CORRECTION_PROB = 0.8

    // ── Error multipliers by word context ─────────────────────────────
    /** Error probability multiplier for complex/long words. */
    const val COMPLEX_WORD_ERROR_MULT = 1.5

    /** Error probability multiplier for common words (lower = fewer errors). */
    const val COMMON_WORD_ERROR_MULT = 0.5

    /** Error probability multiplier for composed-accent characters. */
    const val COMPOSED_ACCENT_ERROR_MULT = 2.0

    // ── Speed factors (multipliers on base keystroke time) ─────────────
    /** Speed boost for common words (< 1.0 = faster). */
    const val SPEED_BOOST_COMMON_WORD = 0.6

    /** Speed penalty for complex words (> 1.0 = slower). */
    const val SPEED_PENALTY_COMPLEX_WORD = 1.3

    /** Speed boost when consecutive keys are physically close. */
    const val SPEED_BOOST_CLOSE_KEYS = 0.5

    /** Speed boost for common bigrams (e.g. "th", "he"). */
    const val SPEED_BOOST_BIGRAM = 0.4

    // ── Key distance thresholds ───────────────────────────────────────
    /** Distance below which keys count as "close". */
    const val CLOSE_KEY_THRESHOLD = 2.0

    /** Distance above which keys count as "far". */
    const val FAR_KEY_THRESHOLD = 4.0

    /** Speed penalty for far-apart keys. */
    const val FAR_KEY_PENALTY = 1.2

    /** Floor multiplier to prevent unrealistic stacking of boosts. */
    const val MIN_SPEED_MULTIPLIER = 0.15

    // ── Time distributions (seconds) ──────────────────────────────────
    /** Gaussian standard deviation on per-keystroke time. */
    const val TIME_KEYSTROKE_STD = 0.03

    /** Mean time for a backspace press. */
    const val TIME_BACKSPACE_MEAN = 0.12

    /** Std dev for backspace press time. */
    const val TIME_BACKSPACE_STD = 0.02

    /** Mean reaction time before starting a correction. */
    const val TIME_REACTION_MEAN = 0.35

    /** Std dev of reaction time. */
    const val TIME_REACTION_STD = 0.1

    // ── Floor values for sampled times ────────────────────────────────
    /** Minimum keystroke time (prevents negative or zero). */
    const val MIN_KEYSTROKE_TIME = 0.02

    /** Minimum reaction time. */
    const val MIN_REACTION_TIME = 0.1

    /** Minimum backspace time. */
    const val MIN_BACKSPACE_TIME = 0.03

    // ── Specific penalties (added to keystroke time) ──────────────────
    /** Extra time for a direct accent key (e.g. é on AZERTY). */
    const val TIME_DIRECT_ACCENT_PENALTY = 0.15

    /** Extra time for a composed accent (dead-key + base). */
    const val TIME_COMPOSED_ACCENT_PENALTY = 0.4

    /** Extra time for uppercase (holding Shift). */
    const val TIME_UPPERCASE_PENALTY = 0.2

    /** Mean extra pause after a space (word boundary). */
    const val TIME_SPACE_PAUSE_MEAN = 0.25

    /** Std dev of space-pause. */
    const val TIME_SPACE_PAUSE_STD = 0.05

    // ── Fatigue model ─────────────────────────────────────────────────
    /** Multiplicative fatigue factor applied per keystroke. */
    const val FATIGUE_FACTOR = 1.0005

    /** Maximum fatigue multiplier (cap). */
    const val FATIGUE_CAP = 1.5
}

package com.wirekey.humantyping

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * A single atomic typing event produced by the Markov simulation.
 *
 * @param timestampSeconds Cumulative time from simulation start (in seconds).
 * @param action           What happened: TYPED, TYPED_ERROR, TYPED_SWAP, or BACKSPACE.
 * @param character        The character(s) involved (empty for BACKSPACE).
 * @param currentText      The full text buffer after this event.
 */
data class TypingEvent(
    val timestampSeconds: Double,
    val action: TypingAction,
    val character: String,
    val currentText: String,
    val mentalCursorPos: Int = 0
)

/** The type of action in a [TypingEvent]. */
enum class TypingAction {
    /** A correct character was typed. */
    TYPED,
    /** A wrong (neighbor) character was typed. */
    TYPED_ERROR,
    /** Two characters were typed in swapped order (anticipation error). */
    TYPED_SWAP,
    /** A backspace was pressed (error correction). */
    BACKSPACE
}

/**
 * Markov-chain human typing simulator.
 * Ported from HumanTyping Python library (typer.py).
 *
 * Given a target text and a target WPM, this class simulates an entire
 * typing session including:
 * - Variable per-keystroke timing based on keyboard geometry, bigrams, word difficulty
 * - Realistic errors (neighbor-key hits, character swaps)
 * - Probabilistic error detection and correction via backspace
 * - Fatigue accumulation over long texts
 *
 * Usage:
 * ```kotlin
 * val typer = MarkovTyper("Hello, world!", targetWpm = 65)
 * val events = typer.run()
 * // events is a List<TypingEvent> you can replay with delays
 * ```
 */
class MarkovTyper(
    private val targetText: String,
    targetWpm: Int = HumanTypingConfig.DEFAULT_WPM
) {
    init {
        require(targetText.isNotEmpty()) { "targetText must be a non-empty string" }
        require(targetWpm > 0) { "targetWpm must be a positive number" }
    }

    private val keyboard = KeyboardLayout()

    // Session-level WPM with Gaussian variation
    private val sessionWpm: Double = max(10.0, gaussianSample(targetWpm.toDouble(), HumanTypingConfig.WPM_STD))
    private val baseKeystrokeTime: Double = 60.0 / (sessionWpm * HumanTypingConfig.AVG_WORD_LENGTH)

    // Mutable simulation state
    private var currentText = StringBuilder()
    private var totalTime = 0.0
    private var lastCharTyped: Char? = null
    private var fatigueMultiplier = 1.0
    private var mentalCursorPos = 0
    private var activeCognitiveTypo: String? = null
    private var cognitiveTypoIndex = 0

    /**
     * Returns the current word the mental cursor is inside of.
     */
    private fun getCurrentWordContext(): String? {
        if (mentalCursorPos >= targetText.length) return null
        var start = mentalCursorPos
        while (start > 0 && targetText[start - 1] != ' ') start--
        var end = mentalCursorPos
        while (end < targetText.length && targetText[end] != ' ') end++
        return targetText.substring(start, end)
    }

    /**
     * Calculates the time for a single keystroke, factoring in:
     * fatigue, word difficulty, bigram proximity, key distance,
     * space pauses, accent penalties, and uppercase penalty.
     */
    private fun calculateKeystrokeTime(charToType: Char): Double {
        var keystrokeTime = baseKeystrokeTime * fatigueMultiplier

        // Word difficulty adjustment
        val currentWord = getCurrentWordContext()
        if (currentWord != null) {
            when (LanguageModel.getWordDifficulty(currentWord)) {
                LanguageModel.WordDifficulty.COMMON -> keystrokeTime *= HumanTypingConfig.SPEED_BOOST_COMMON_WORD
                LanguageModel.WordDifficulty.COMPLEX -> keystrokeTime *= HumanTypingConfig.SPEED_PENALTY_COMPLEX_WORD
                LanguageModel.WordDifficulty.NORMAL -> { /* no adjustment */ }
            }
        }

        // Bigram / key-distance adjustment
        val prevChar = lastCharTyped
        if (prevChar != null) {
            if (LanguageModel.isCommonBigram(prevChar, charToType)) {
                keystrokeTime *= HumanTypingConfig.SPEED_BOOST_BIGRAM
            } else {
                val dist = keyboard.getDistance(prevChar, charToType)
                if (dist > 0 && dist < HumanTypingConfig.CLOSE_KEY_THRESHOLD) {
                    keystrokeTime *= HumanTypingConfig.SPEED_BOOST_CLOSE_KEYS
                } else if (dist > HumanTypingConfig.FAR_KEY_THRESHOLD) {
                    keystrokeTime *= HumanTypingConfig.FAR_KEY_PENALTY
                }
            }
        }

        // Character-type-specific penalties
        when {
            charToType == ' ' -> keystrokeTime += gaussianSample(
                HumanTypingConfig.TIME_SPACE_PAUSE_MEAN,
                HumanTypingConfig.TIME_SPACE_PAUSE_STD
            )
            keyboard.isComposedAccent(charToType) -> keystrokeTime += HumanTypingConfig.TIME_COMPOSED_ACCENT_PENALTY
            keyboard.isDirectAccent(charToType) -> keystrokeTime += HumanTypingConfig.TIME_DIRECT_ACCENT_PENALTY
            charToType.isUpperCase() -> keystrokeTime += HumanTypingConfig.TIME_UPPERCASE_PENALTY
        }

        // Apply floor to prevent unrealistic stacking of boosts
        keystrokeTime = max(HumanTypingConfig.MIN_SPEED_MULTIPLIER * baseKeystrokeTime, keystrokeTime)

        val dt = gaussianSample(keystrokeTime, HumanTypingConfig.TIME_KEYSTROKE_STD)
        return max(HumanTypingConfig.MIN_KEYSTROKE_TIME, dt)
    }

    /**
     * Advances the simulation by one atomic step.
     * Returns the [TypingEvent] produced, or `null` if the text is complete.
     */
    fun step(): TypingEvent? {
        // 1. Check for completion
        if (currentText.toString() == targetText) return null

        // ── MONITORING & CORRECTION PHASE ──

        // Calculate divergence point
        var firstErrorPos = targetText.length
        val minLen = min(currentText.length, targetText.length)
        for (i in 0 until minLen) {
            if (currentText[i] != targetText[i]) {
                firstErrorPos = i
                break
            }
        }

        // Over-typing past target length is also an error
        if (currentText.length > targetText.length && firstErrorPos == targetText.length) {
            firstErrorPos = targetText.length
        }

        val finishedCognitiveTypo = activeCognitiveTypo != null && cognitiveTypoIndex >= activeCognitiveTypo!!.length

        // Do we have an error, OR did we just finish a cognitive typo that needs correction?
        if (firstErrorPos < currentText.length || finishedCognitiveTypo) {
            var shouldCorrect = false
            val lastAction = history.lastOrNull()?.action

            // Case 0: Continued backspacing (critical — don't stop mid-correction)
            if (lastAction == TypingAction.BACKSPACE) {
                shouldCorrect = true
            }
            // Case A: End of text (always correct)
            else if (mentalCursorPos >= targetText.length) {
                shouldCorrect = true
            }
            // Case B: Context checks
            else if (currentText.isNotEmpty()) {
                val lastChar = currentText.last()
                val distance = currentText.length - firstErrorPos

                // Word boundary check (strict)
                if (lastChar in " \n\t.,;!?:()[]{}\"'<>") {
                    shouldCorrect = true
                }
                // Drift check (≥2 chars past error)
                else if (distance >= 2) {
                    if (Random.nextDouble() < HumanTypingConfig.DRIFT_CORRECTION_PROB) {
                        shouldCorrect = true
                    }
                }
                // Immediate reaction (1 char past error)
                else if (distance == 1) {
                    if (Random.nextDouble() < HumanTypingConfig.PROB_NOTICE_ERROR) {
                        shouldCorrect = true
                    }
                }
            }

            // ── Cognitive Typo Override ──
            if (activeCognitiveTypo != null) {
                if (cognitiveTypoIndex < activeCognitiveTypo!!.length) {
                    shouldCorrect = false // Suppress correction until the full typo is typed
                } else {
                    shouldCorrect = true  // Force correction once fully typed
                    
                    // Extra cognitive delay (simulate "wait, that's wrong" realization)
                    // We add an extra 300-700ms (Gaussian around 0.5s) on top of normal reaction time
                    if (lastAction != TypingAction.BACKSPACE) {
                        val cognitiveDelay = max(0.3, gaussianSample(0.5, 0.1))
                        totalTime += cognitiveDelay
                    }
                    
                    activeCognitiveTypo = null // Clear state so backspacing/typing proceeds normally
                }
            }

            if (shouldCorrect) {
                // Reaction time (only if not already in a backspace chain)
                if (lastAction != TypingAction.BACKSPACE) {
                    val dt = gaussianSample(HumanTypingConfig.TIME_REACTION_MEAN, HumanTypingConfig.TIME_REACTION_STD)
                    totalTime += max(HumanTypingConfig.MIN_REACTION_TIME, dt)
                }

                // Perform backspace
                val dt = max(
                    HumanTypingConfig.MIN_BACKSPACE_TIME,
                    gaussianSample(HumanTypingConfig.TIME_BACKSPACE_MEAN, HumanTypingConfig.TIME_BACKSPACE_STD)
                )
                totalTime += dt
                currentText.deleteCharAt(currentText.length - 1)

                // Sync mental cursor immediately
                mentalCursorPos = currentText.length

                val event = TypingEvent(totalTime, TypingAction.BACKSPACE, "", currentText.toString(), mentalCursorPos)
                history.add(event)

                return event
            }
        }

        // ── TYPING PHASE ──

        // Sync mental cursor if we backspaced
        if (mentalCursorPos > currentText.length) {
            mentalCursorPos = currentText.length
        }

        if (mentalCursorPos >= targetText.length && activeCognitiveTypo == null) return null

        // ── Cognitive Muscle-Memory Error Check ──
        if (activeCognitiveTypo == null && Random.nextDouble() < HumanTypingConfig.PROB_COGNITIVE_ERROR) {
            val matches = CognitiveDictionary.filter { targetText.startsWith(it.first, startIndex = mentalCursorPos) }
            if (matches.isNotEmpty()) {
                // Prefer the longest (most specific) match — shorter entries are often
                // prefixes of longer ones (e.g. "==" of "==="), which would otherwise
                // cause a false-positive "typo" on text that was already correct.
                val longestLen = matches.maxOf { it.first.length }
                val match = matches.filter { it.first.length == longestLen }.random()
                val isStart = mentalCursorPos == 0 || !targetText[mentalCursorPos - 1].isLetterOrDigit()
                val isSymbol = !match.first[0].isLetterOrDigit()
                if (isStart || isSymbol) {
                    activeCognitiveTypo = match.second
                    cognitiveTypoIndex = 0
                }
            }
        }

        val isCognitiveTyping = activeCognitiveTypo != null && cognitiveTypoIndex < activeCognitiveTypo!!.length
        val charIntended = if (isCognitiveTyping) {
            val c = activeCognitiveTypo!![cognitiveTypoIndex]
            cognitiveTypoIndex++
            c
        } else {
            targetText[mentalCursorPos]
        }

        // If the character is not on our keyboard, or we are in a cognitive typo, bypass normal error models
        if (isCognitiveTyping || (!keyboard.hasKey(charIntended) && charIntended != ' ')) {
            fatigueMultiplier = min(HumanTypingConfig.FATIGUE_CAP, fatigueMultiplier * HumanTypingConfig.FATIGUE_FACTOR)
            val dt = max(
                HumanTypingConfig.MIN_KEYSTROKE_TIME,
                gaussianSample(baseKeystrokeTime * fatigueMultiplier, HumanTypingConfig.TIME_KEYSTROKE_STD)
            )
            totalTime += dt
            currentText.append(charIntended)
            lastCharTyped = charIntended
            val action = if (isCognitiveTyping) TypingAction.TYPED_ERROR else TypingAction.TYPED
            val event = TypingEvent(totalTime, action, charIntended.toString(), currentText.toString(), mentalCursorPos)
            history.add(event)
            mentalCursorPos++
            return event
        }

        fatigueMultiplier = min(HumanTypingConfig.FATIGUE_CAP, fatigueMultiplier * HumanTypingConfig.FATIGUE_FACTOR)

        // ── Shift-Key Synchronization Error (CamelCase / PascalCase) ──
        if (mentalCursorPos > 0 && mentalCursorPos < targetText.length && activeCognitiveTypo == null) {
            val intended = targetText[mentalCursorPos]
            val prevChar = targetText[mentalCursorPos - 1]
            
            if (prevChar.isUpperCase() && intended.isLowerCase() && intended.isLetter()) {
                val isStartOfWord = mentalCursorPos == 1 || !targetText[mentalCursorPos - 2].isLetter()
                if (isStartOfWord) {
                    val currentWord = getCurrentWordContext() ?: ""
                    val isDsaTarget = DsaDictionary.contains(currentWord.lowercase())
                    
                    val prob = if (isDsaTarget) HumanTypingConfig.PROB_SHIFT_SYNC_ERROR * 1.5 else HumanTypingConfig.PROB_SHIFT_SYNC_ERROR
                    
                    if (Random.nextDouble() < prob) {
                        val wrongChar = intended.uppercaseChar()
                        val dt = calculateKeystrokeTime(wrongChar)
                        totalTime += dt
                        currentText.append(wrongChar)
                        lastCharTyped = wrongChar
                        val event = TypingEvent(totalTime, TypingAction.TYPED_ERROR, wrongChar.toString(), currentText.toString(), mentalCursorPos)
                        history.add(event)
                        mentalCursorPos++
                        return event
                    }
                }
            }
        }

        // ── Swap Error (Anticipation) ──
        val isLeadingWhitespace = charIntended == ' ' && (mentalCursorPos == 0 || targetText.substring(0, mentalCursorPos).takeLastWhile { it != '\n' }.all { it == ' ' })
        if (targetText.length > mentalCursorPos + 1 && !isLeadingWhitespace) {
            val charAfter = targetText[mentalCursorPos + 1]
            if (charAfter != ' ' && charAfter != charIntended) {
                if (Random.nextDouble() < HumanTypingConfig.PROB_SWAP_ERROR) {
                    // Type the anticipated character first
                    val dt1 = calculateKeystrokeTime(charAfter)
                    totalTime += dt1
                    currentText.append(charAfter)

                    // Then type the intended character (producing a real swap)
                    val dt2 = calculateKeystrokeTime(charIntended)
                    totalTime += dt2
                    currentText.append(charIntended)

                    lastCharTyped = charIntended
                    val event = TypingEvent(
                        totalTime,
                        TypingAction.TYPED_SWAP,
                        "$charAfter$charIntended",
                        currentText.toString(),
                        mentalCursorPos
                    )
                    history.add(event)
                    mentalCursorPos += 2
                    return event
                }
            }
        }

        // ── Normal Typing (Success or Error) ──
        var currentProbError = if (isLeadingWhitespace) 0.0 else HumanTypingConfig.PROB_ERROR
        
        if (!isLeadingWhitespace) {
            val wordDiff = LanguageModel.getWordDifficulty(getCurrentWordContext() ?: "")
            when (wordDiff) {
                LanguageModel.WordDifficulty.COMPLEX -> currentProbError *= HumanTypingConfig.COMPLEX_WORD_ERROR_MULT
                LanguageModel.WordDifficulty.COMMON -> currentProbError *= HumanTypingConfig.COMMON_WORD_ERROR_MULT
                LanguageModel.WordDifficulty.NORMAL -> { /* no adjustment */ }
            }
            if (keyboard.isComposedAccent(charIntended)) {
                currentProbError *= HumanTypingConfig.COMPOSED_ACCENT_ERROR_MULT
            }
        }

        return if (Random.nextDouble() < currentProbError) {
            // Generate Error — hit a neighboring key
            val wrongChar = keyboard.getRandomNeighbor(charIntended)
            val dt = calculateKeystrokeTime(wrongChar)
            totalTime += dt
            currentText.append(wrongChar)
            lastCharTyped = wrongChar
            val event = TypingEvent(totalTime, TypingAction.TYPED_ERROR, wrongChar.toString(), currentText.toString(), mentalCursorPos)
            history.add(event)
            mentalCursorPos++
            event
        } else {
            // Success — type the correct character
            val dt = calculateKeystrokeTime(charIntended)
            totalTime += dt
            currentText.append(charIntended)
            lastCharTyped = charIntended
            val event = TypingEvent(totalTime, TypingAction.TYPED, charIntended.toString(), currentText.toString(), mentalCursorPos)
            history.add(event)
            mentalCursorPos++
            event
        }
    }

    /** Internal history of all events. */
    private val history = mutableListOf<TypingEvent>()

    /**
     * Runs the simulation to completion.
     * Returns the ordered list of [TypingEvent]s representing the full trajectory.
     *
     * The trajectory includes correct keystrokes, errors, and backspace corrections.
     * When replayed with the embedded delays, the output on the host will look like
     * a real human typing — including visible typos being fixed.
     */
    fun run(): List<TypingEvent> {
        var steps = 0
        val maxSteps = targetText.length * 10 // safety limit
        while (step() != null) {
            steps++
            if (steps > maxSteps) break
        }
        return history.toList()
    }

    companion object {
        /**
         * Sample from a Gaussian distribution using the Box-Muller transform.
         * Kotlin's [Random] only provides uniform distribution, so we implement
         * this ourselves to match the Python `numpy.random.normal()` behavior.
         */
        internal fun gaussianSample(mean: Double, stdDev: Double): Double {
            // Box-Muller transform.
            // Clamp u1 away from 0.0 to avoid ln(0) = -Infinity → NaN.
            val u1 = Random.nextDouble().coerceAtLeast(1e-10)
            val u2 = Random.nextDouble()
            val z = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
            return mean + z * stdDev
        }

        /**
         * Dictionary of common DSA and coding targets to use as mapping targets.
         * The engine slightly boosts the probability of Shift-Key sync errors for these terms.
         */
        private val DsaDictionary = setOf(
            // Data Types / Classes
            "String", "Integer", "HashMap", "LinkedList", "ArrayList", "TreeNode", 
            "ListNode", "PriorityQueue", "SweepLine", "Stack", "Queue", "Graph", "Matrix",
            // Keywords / Booleans
            "True", "False", "Null", "None", "Return", "While", "Break", "Continue", 
            "Catch", "Finally", "Private", "Public", "Static",
            // Common DSA Variables / Methods
            "Ans", "Result", "Count", "Length", "Size", "Mid", "Left", "Right", 
            "Max", "Min", "System.out", "Console.log", "ToString", "HasNext"
        ).map { it.lowercase() }.toSet()

        /**
         * Dictionary of common cognitive muscle-memory errors (intended to typo).
         */
        private val CognitiveDictionary = listOf(
            "i--" to "i++",
            "j--" to "j++",
            "i += 2" to "i++",
            "left++" to "left--",
            "right--" to "right++",
            "arr.length" to "arr.length()",
            "arr.length()" to "arr.length",
            "str.length()" to "str.length",
            "list.size()" to "list.length",
            "list.size()" to "list.size",
            "===" to "==",
            "==" to "=",
            "<=" to "<",
            ">=" to ">",
            ":" to "{",
            "elif" to "else if",
            "System.out.print(" to "System.out.println(",
            "Math.max(" to "math.max(",
            "push_back" to "push",
            "append" to "add"
        )
    }
}

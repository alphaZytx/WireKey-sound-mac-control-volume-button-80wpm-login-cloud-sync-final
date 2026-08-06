package com.wirekey.util

import com.wirekey.humantyping.HumanTypingConfig
import com.wirekey.humantyping.MarkovTyper
import com.wirekey.humantyping.TypingAction
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Controls the pacing of compose-mode keystroke dispatch.
 *
 * @param targetWpm         Target words per minute. Clamped to [20, 150].
 * @param humanTypingEnabled When true, uses the full Markov-chain simulation
 *                           with realistic errors, corrections, and variable timing.
 *                           When false, falls back to simple uniform-delay mode.
 * @param naturalTypingEnabled Adds contextual pauses at word/sentence boundaries
 *                              (only used in simple mode as a secondary control;
 *                              the Markov model handles this intrinsically).
 */
data class CadenceSettings(
    val targetWpm: Int = HumanTypingConfig.DEFAULT_WPM,
    val humanTypingEnabled: Boolean = true,
    val naturalTypingEnabled: Boolean = true,
    val variability: Float = 0.15f,
    val codeEditorMode: Boolean = true
) {
    companion object {
        val DEFAULT = CadenceSettings()
    }
}

/**
 * An atomic action in a typing plan — either type a character or press backspace.
 *
 * @param delayMs  Milliseconds to wait *before* executing this action.
 * @param char     The character to type (null for backspace).
 * @param isBackspace True if this action is a backspace press.
 */
data class PlannedKeystroke(
    val delayMs: Long,
    val char: Char?,
    val isBackspace: Boolean,
    val originalIndex: Int? = null
)

object TypingCadenceEngine {

    // ── Simple mode constants (legacy fallback) ───────────────────────
    private const val WORD_BOUNDARY_MULTIPLIER = 1.4f
    private const val SENTENCE_END_MULTIPLIER = 2.2f
    private const val MIN_DELAY_MS = 15L
    private const val MAX_DELAY_MS = 600L

    /**
     * Generate a complete typing plan for the given text.
     *
     * When [CadenceSettings.humanTypingEnabled] is `true`, this runs the
     * full Markov-chain simulation (which may produce errors, corrections,
     * and retypes) and converts the event timeline into a list of
     * [PlannedKeystroke]s with inter-event delays in milliseconds.
     *
     * When `false`, falls back to simple per-character delay calculation.
     */
    fun generateTypingPlan(text: String, settings: CadenceSettings): List<PlannedKeystroke> {
        if (text.isEmpty()) return emptyList()

        val plan = if (settings.humanTypingEnabled) {
            generateHumanPlan(text, settings.targetWpm)
        } else {
            generateSimplePlan(text, settings)
        }
        
        val decelerated = applySyntaxDeceleration(plan)
        val indented = simulateAutoIndentAndTrim(decelerated, settings.codeEditorMode)
        return applyWpmCalibration(indented, settings.targetWpm)
    }

    /**
     * Symbol & Complex Syntax Deceleration:
     * Slows down typing speed natively for awkward programming symbols,
     * shift-key reaches, and dense syntax chunks (like :: or ->).
     */
    private fun applySyntaxDeceleration(plan: List<PlannedKeystroke>): List<PlannedKeystroke> {
        val decelerated = mutableListOf<PlannedKeystroke>()
        
        // 1. Specific arrays/sets of high-friction symbols
        val highFrictionSymbols = setOf('_', '<', '>', ':', ';', '(', ')', '{', '}', '[', ']', '&', '|', '+', '-', '=', '!')
        val distantShiftKeys = setOf('*', '&', '_', '^', '%', '$', '#', '@', '!', '>', '<', ':', '?', '{', '}', '|')
        
        val syntaxChunks = setOf("->", "::", "()", "&&", "||", "==", "!=", ">=", "<=")
        
        var previousChar: Char? = null
        
        for (i in plan.indices) {
            val stroke = plan[i]
            if (stroke.isBackspace || stroke.char == null) {
                decelerated.add(stroke)
                previousChar = null
                continue
            }
            
            val c = stroke.char
            var baseDelay = stroke.delayMs.toDouble()
            
            // 3. Syntax Chunking Detection
            var isChunk = false
            if (i < plan.size - 1) {
                var peek = i + 1
                while (peek < plan.size) {
                    val nextStroke = plan[peek]
                    if (nextStroke.isBackspace) break
                    val nextC = nextStroke.char
                    if (nextC != null) {
                        val pair = "$c$nextC"
                        if (pair in syntaxChunks) isChunk = true
                        break
                    }
                    peek++
                }
            }
            if (previousChar != null) {
                val pair = "$previousChar$c"
                if (pair in syntaxChunks) isChunk = true
            }
            
            // 1. Symbol Penalties
            var multiplier = 1.0
            if (c in highFrictionSymbols) {
                multiplier = MarkovTyper.gaussianSample(2.1, 0.3) // Transition TO symbol
            } else if (previousChar in highFrictionSymbols) {
                multiplier = MarkovTyper.gaussianSample(1.5, 0.2) // Transition FROM symbol
            }
            
            if (isChunk) {
                // High friction zone: slow down local token cluster
                multiplier *= 1.4
            }
            
            baseDelay *= multiplier
            
            // 2. Shift-Key Micro-Pauses
            if (c in distantShiftKeys) {
                val shiftPause = MarkovTyper.gaussianSample(125.0, 20.0)
                baseDelay += shiftPause
            }
            
            decelerated.add(stroke.copy(delayMs = baseDelay.toLong()))
            previousChar = c
        }
        
        return decelerated
    }

    /**
     * WPM Calibration Engine: Dynamically adjusts standard character delays to ensure
     * the overall output accurately hits the target WPM, compensating for macro-pauses
     * and backspace correction chains.
     */
    private fun applyWpmCalibration(plan: List<PlannedKeystroke>, targetWpm: Int): List<PlannedKeystroke> {
        // 1. Base Math: Standardize the base keystroke delay
        val wpm = targetWpm.coerceIn(20, 150)
        val baseDelayMs = 60000.0 / (wpm * 5)
        
        var currentVirtualLength = 0
        var totalActualTime = 0.0
        
        val calibrated = mutableListOf<PlannedKeystroke>()
        
        for (stroke in plan) {
            var adjustedDelay = stroke.delayMs.toDouble()
            
            // 2. The Compensation Pool: difference between actual time spent and ideal time for current net length
            val idealTime = currentVirtualLength * baseDelayMs
            val compensationPoolMs = totalActualTime - idealTime
            
            // 3. Dynamic Scaling: For standard alphanumeric characters
            val isAlphaNum = !stroke.isBackspace && stroke.char != null && stroke.char.isLetterOrDigit()
            
            if (isAlphaNum && compensationPoolMs > 0) {
                // Slightly reduce the delay to "catch up" (max 15% reduction of base delay)
                val maxCatchUp = baseDelayMs * 0.15 
                val catchUp = minOf(maxCatchUp, compensationPoolMs)
                
                adjustedDelay -= catchUp
                
                // 4. Boundaries: Never drop below 30ms inter-key delay
                if (adjustedDelay < 30.0) {
                    adjustedDelay = 30.0
                }
            }
            
            // Update state for the NEXT keystroke's pool calculation
            if (stroke.isBackspace) {
                if (currentVirtualLength > 0) currentVirtualLength--
            } else if (stroke.char != null) {
                currentVirtualLength++
            }
            
            totalActualTime += adjustedDelay
            calibrated.add(stroke.copy(delayMs = adjustedDelay.toLong()))
        }
        
        return calibrated
    }

    /**
     * 1. Carriage Return Fix: Resets absolute X-position logic after \n.
     * 2. Smart Indent Recalculation: Accurately predicts IDE auto-indent.
     * 3. Code-Aware Trimming: Prevents redundant spaces and fixes the waterfall effect.
     */
    private fun simulateAutoIndentAndTrim(plan: List<PlannedKeystroke>, codeEditorMode: Boolean): List<PlannedKeystroke> {
        if (!codeEditorMode) {
            // Simple fallback: just zero out delays for leading spaces
            val optimized = plan.toMutableList()
            var isLeading = true
            for (i in optimized.indices) {
                val stroke = optimized[i]
                if (stroke.isBackspace) isLeading = false
                else if (stroke.char == '\n') isLeading = true
                else if (stroke.char == ' ' && isLeading) optimized[i] = stroke.copy(delayMs = 0L)
                else isLeading = false
            }
            return optimized
        }

        val optimized = mutableListOf<PlannedKeystroke>()
        
        var virtualX = 0
        var previousLineIndent = 0
        var lastNonWsChar: Char? = null
        
        var isLeading = true
        var i = 0
        
        // ── Line Transition Cadence ──
        var pendingLineTransitionDelay = 0L
        
        fun addStroke(stroke: PlannedKeystroke) {
            if (pendingLineTransitionDelay > 0) {
                optimized.add(stroke.copy(delayMs = stroke.delayMs + pendingLineTransitionDelay))
                pendingLineTransitionDelay = 0L
            } else {
                optimized.add(stroke)
            }
        }
        
        while (i < plan.size) {
            val stroke = plan[i]
            
            if (stroke.isBackspace) {
                addStroke(stroke)
                if (virtualX > 0) virtualX--
                isLeading = false // Backspaces organically break the leading auto-indent phase
                i++
                continue
            }
            
            val c = stroke.char
            if (c == '\n') {
                addStroke(stroke)
                
                // IDE Carriage Return: The cursor jumps to the next line, and the IDE auto-indents based on previous context.
                virtualX = previousLineIndent
                if (lastNonWsChar == '{' || lastNonWsChar == '[' || lastNonWsChar == '(') {
                    virtualX += 4
                }
                
                // Calculate Line Transition Cadence
                var peek = i + 1
                var firstNonWsCharOnNextLine: Char? = null
                while (peek < plan.size) {
                    val nextStroke = plan[peek]
                    if (nextStroke.isBackspace) break
                    val nextC = nextStroke.char
                    if (nextC == ' ' || nextC == '\t') {
                        peek++
                    } else {
                        firstNonWsCharOnNextLine = nextC
                        break
                    }
                }
                
                var delay = kotlin.math.max(400L, kotlin.math.min(1500L, (MarkovTyper.gaussianSample(0.8, 0.2) * 1000).toLong()))
                if (lastNonWsChar == '{') {
                    delay += (MarkovTyper.gaussianSample(0.3, 0.1) * 1000).toLong()
                }
                if (firstNonWsCharOnNextLine == '}') {
                    delay = kotlin.math.max(200L, kotlin.math.min(500L, (MarkovTyper.gaussianSample(0.3, 0.05) * 1000).toLong()))
                }
                pendingLineTransitionDelay = delay
                
                isLeading = true
                lastNonWsChar = null
                i++
            } else if (isLeading) {
                // We are at the start of the line, gathering all leading whitespace (if any)
                var targetLineIndent = 0
                while (i < plan.size) {
                    val nextStroke = plan[i]
                    if (nextStroke.isBackspace) break // Break on organic errors
                    
                    val nextC = nextStroke.char
                    if (nextC == ' ') { targetLineIndent += 1; i++ } 
                    else if (nextC == '\t') { targetLineIndent += 4; i++ } 
                    else { break }
                }
                
                previousLineIndent = targetLineIndent
                
                // Smart Indent Recalculation & Trimming
                if (targetLineIndent > virtualX) {
                    val spacesToType = targetLineIndent - virtualX
                    for (s in 0 until spacesToType) {
                        addStroke(PlannedKeystroke(delayMs = 0L, char = ' ', isBackspace = false))
                    }
                }
                // Removed the blind backspace injection to prevent data loss (e.g., deleting previous lines) 
                // when the IDE's actual auto-indent differs from virtualX.
                
                virtualX = maxOf(targetLineIndent, virtualX)
                isLeading = false
            } else {
                addStroke(stroke)
                if (c != null) {
                    virtualX++
                    if (!c.isWhitespace()) {
                        lastNonWsChar = c
                    }
                }
                i++
            }
        }
        
        return optimized
    }

    /**
     * Markov-chain based plan: runs the full simulation and converts
     * the event timeline to PlannedKeystroke list.
     */
    private fun generateHumanPlan(text: String, targetWpm: Int): List<PlannedKeystroke> {
        val wpm = targetWpm.coerceIn(20, 150)
        val typer = MarkovTyper(text, targetWpm = wpm)
        val events = typer.run()

        if (events.isEmpty()) return emptyList()

        val plan = mutableListOf<PlannedKeystroke>()
        var lastTimestamp = 0.0

        for (event in events) {
            val delaySeconds = event.timestampSeconds - lastTimestamp
            val delayMs = (delaySeconds * 1000).roundToLong().coerceAtLeast(0L)
            lastTimestamp = event.timestampSeconds

            when (event.action) {
                TypingAction.BACKSPACE -> {
                    plan.add(PlannedKeystroke(delayMs = delayMs, char = null, isBackspace = true, originalIndex = event.mentalCursorPos))
                }
                TypingAction.TYPED, TypingAction.TYPED_ERROR -> {
                    val char = event.character.firstOrNull()
                    if (char != null) {
                        plan.add(PlannedKeystroke(delayMs = delayMs, char = char, isBackspace = false, originalIndex = event.mentalCursorPos))
                    }
                }
                TypingAction.TYPED_SWAP -> {
                    // Swap events contain 2 characters typed in rapid succession.
                    // The delay is for the first character; second gets a minimal gap.
                    val chars = event.character
                    if (chars.length >= 2) {
                        plan.add(PlannedKeystroke(delayMs = delayMs, char = chars[0], isBackspace = false, originalIndex = event.mentalCursorPos))
                        plan.add(PlannedKeystroke(delayMs = 0L, char = chars[1], isBackspace = false, originalIndex = event.mentalCursorPos + 1))
                    }
                }
            }
        }

        return plan
    }

    /**
     * Simple delay-based plan (legacy fallback mode):
     * uniform base delay with contextual multipliers and jitter.
     */
    private fun generateSimplePlan(text: String, settings: CadenceSettings): List<PlannedKeystroke> {
        val plan = mutableListOf<PlannedKeystroke>()
        var previousChar: Char? = null

        for (i in text.indices) {
            val char = text[i]
            val delayMs = if (previousChar != null) {
                delayForChar(char, previousChar, settings)
            } else {
                0L
            }
            plan.add(PlannedKeystroke(delayMs = delayMs, char = char, isBackspace = false, originalIndex = i))
            previousChar = char
        }

        return plan
    }

    /**
     * Returns the number of milliseconds to wait *before* sending [char].
     * Used in simple (non-human) mode and kept for backward compatibility.
     *
     * @param char          The character about to be sent.
     * @param previousChar  The character that was just sent (null for the first char).
     * @param settings      Speed and variability knobs.
     */
    fun delayForChar(
        char: Char,
        previousChar: Char?,
        settings: CadenceSettings
    ): Long {
        // Convert WPM to base delay: WPM → chars/min → ms/char
        // WPM * 5 chars/word = chars/min → 60000 / chars_per_min = ms/char
        val baseDelayMs = (60000.0 / (settings.targetWpm.coerceIn(20, 150) * 5)).roundToLong()

        // Apply contextual multipliers
        val contextual = when {
            settings.naturalTypingEnabled && previousChar != null && previousChar in ".!?" && char == ' ' ->
                (baseDelayMs * SENTENCE_END_MULTIPLIER).roundToLong()
            settings.naturalTypingEnabled && previousChar == ' ' ->
                (baseDelayMs * WORD_BOUNDARY_MULTIPLIER).roundToLong()
            else -> baseDelayMs
        }

        // Apply random jitter
        val maxSwing = settings.variability
        val jitterFactor = 1.0f + Random.nextFloat() * 2 * maxSwing - maxSwing
        val jittered = (contextual * jitterFactor).roundToLong()

        return jittered.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }
}

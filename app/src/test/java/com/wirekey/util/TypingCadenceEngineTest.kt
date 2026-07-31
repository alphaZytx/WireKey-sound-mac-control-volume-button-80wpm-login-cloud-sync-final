package com.wirekey.util

import org.junit.Assert.*
import org.junit.Test

class TypingCadenceEngineTest {

    @Test
    fun delayIsWithinClampedRange() {
        val settings = CadenceSettings(targetWpm = 60, variability = 1.0f, humanTypingEnabled = false)
        repeat(200) {
            val delay = TypingCadenceEngine.delayForChar('a', 'b', settings)
            assertTrue("Delay $delay should be >= 15", delay >= 15)
            assertTrue("Delay $delay should be <= 600", delay <= 600)
        }
    }

    @Test
    fun fasterSpeedProducesShorterDelays() {
        val fast = CadenceSettings(targetWpm = 120, variability = 0.0f, humanTypingEnabled = false)
        val slow = CadenceSettings(targetWpm = 30, variability = 0.0f, humanTypingEnabled = false)

        val fastDelay = TypingCadenceEngine.delayForChar('a', 'b', fast)
        val slowDelay = TypingCadenceEngine.delayForChar('a', 'b', slow)

        assertTrue("Fast ($fastDelay) should be less than slow ($slowDelay)", fastDelay < slowDelay)
    }

    @Test
    fun wordBoundaryIsLongerThanMidWord() {
        val settings = CadenceSettings(targetWpm = 60, variability = 0.0f, humanTypingEnabled = false)

        // previousChar=' ' means we just crossed a word boundary
        val wordDelay = TypingCadenceEngine.delayForChar('h', ' ', settings)
        val midWordDelay = TypingCadenceEngine.delayForChar('e', 'h', settings)

        assertTrue("Word boundary ($wordDelay) should exceed mid-word ($midWordDelay)", wordDelay > midWordDelay)
    }

    @Test
    fun sentenceEndIsLongerThanWordBoundary() {
        val settings = CadenceSettings(targetWpm = 60, variability = 0.0f, humanTypingEnabled = false)

        // ". " pattern → sentence-end pause
        val sentenceDelay = TypingCadenceEngine.delayForChar(' ', '.', settings)
        val wordDelay = TypingCadenceEngine.delayForChar('h', ' ', settings)

        assertTrue("Sentence end ($sentenceDelay) should exceed word boundary ($wordDelay)", sentenceDelay > wordDelay)
    }

    @Test
    fun naturalTypingDisabledIgnoresContextualPauses() {
        val disabledSettings = CadenceSettings(
            targetWpm = 60,
            variability = 0.0f,
            naturalTypingEnabled = false,
            humanTypingEnabled = false
        )
        val baseDelay = TypingCadenceEngine.delayForChar('a', 'b', disabledSettings)
        val wordDelay = TypingCadenceEngine.delayForChar('h', ' ', disabledSettings)
        val sentenceDelay = TypingCadenceEngine.delayForChar(' ', '.', disabledSettings)

        assertEquals("Word boundary should equal base delay when natural typing disabled", baseDelay, wordDelay)
        assertEquals("Sentence boundary should equal base delay when natural typing disabled", baseDelay, sentenceDelay)
    }

    @Test
    fun zeroVariabilityProducesConsistentDelays() {
        val settings = CadenceSettings(targetWpm = 60, variability = 0.0f, humanTypingEnabled = false)

        val delays = (1..50).map { TypingCadenceEngine.delayForChar('t', 'e', settings) }
        val uniqueDelays = delays.toSet()

        assertEquals("Zero variability should produce identical delays", 1, uniqueDelays.size)
    }

    @Test
    fun highVariabilityProducesVariedDelays() {
        val settings = CadenceSettings(targetWpm = 60, variability = 1.0f, humanTypingEnabled = false)

        val delays = (1..100).map { TypingCadenceEngine.delayForChar('t', 'e', settings) }
        val uniqueDelays = delays.toSet()

        assertTrue("High variability should produce multiple distinct values, got ${uniqueDelays.size}", uniqueDelays.size > 5)
    }

    @Test
    fun extremeSpeedMultiplierIsClamped() {
        val tooFast = CadenceSettings(targetWpm = 1000, variability = 0.0f, humanTypingEnabled = false)
        val tooSlow = CadenceSettings(targetWpm = 1, variability = 0.0f, humanTypingEnabled = false)

        val fastDelay = TypingCadenceEngine.delayForChar('a', 'b', tooFast)
        val slowDelay = TypingCadenceEngine.delayForChar('a', 'b', tooSlow)

        assertTrue("Even extreme fast should be >= 15ms, got $fastDelay", fastDelay >= 15)
        assertTrue("Even extreme slow should be <= 600ms, got $slowDelay", slowDelay <= 600)
    }

    @Test
    fun firstCharacterWithNullPrevious() {
        val settings = CadenceSettings(humanTypingEnabled = false)
        val delay = TypingCadenceEngine.delayForChar('H', null, settings)
        assertTrue("First char delay $delay should be in range", delay in 15..600)
    }
}

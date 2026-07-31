package com.wirekey.humantyping

import java.text.Normalizer
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * QWERTY keyboard layout model for typing simulation.
 * Ported from HumanTyping Python library (keyboard.py).
 *
 * Provides key-position lookups, distance calculations between keys,
 * neighbor-key detection (for realistic typo generation), and accent handling.
 */
class KeyboardLayout {

    /** The QWERTY grid as rows of characters. */
    private val grid: List<List<Char>> = listOf(
        "`1234567890-=".toList(),
        "qwertyuiop[]\\".toList(),
        "asdfghjkl;'".toList(),
        "zxcvbnm,./".toList()
    )

    /** Map from character → (row, col) on the grid. */
    private val posMap: Map<Char, Pair<Int, Int>> = buildPosMap()

    /**
     * QWERTY has no direct accent keys; all accented characters require
     * a compose/dead-key sequence.
     */
    private val directAccents: Set<Char> = emptySet()
    private val composedAccents: Set<Char> = "âêîôûäëïöüéèàùç".toSet()

    private fun buildPosMap(): Map<Char, Pair<Int, Int>> {
        val mapping = mutableMapOf<Char, Pair<Int, Int>>()
        for ((r, row) in grid.withIndex()) {
            for ((c, char) in row.withIndex()) {
                mapping[char] = Pair(r, c)
            }
        }
        return mapping
    }

    /**
     * Normalize a character for position lookup:
     * lowercase it, and strip accent marks to find the base letter.
     */
    private fun normalizeChar(char: Char): Char {
        val lower = char.lowercaseChar()
        if (lower in composedAccents) {
            val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
            return decomposed.firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                ?: lower
        }
        return lower
    }

    /** Check if a character exists on this keyboard layout. */
    fun hasKey(char: Char): Boolean = normalizeChar(char) in posMap

    /** Return the neighboring keys for a given character. */
    fun getNeighborKeys(char: Char): List<Char> {
        val normalized = normalizeChar(char)
        val pos = posMap[normalized] ?: return emptyList()
        val (r, c) = pos
        val neighbors = mutableListOf<Char>()

        val deltas = listOf(
            -1 to -1, -1 to 0, -1 to 1,
             0 to -1,           0 to 1,
             1 to -1,  1 to 0,  1 to 1
        )

        for ((dr, dc) in deltas) {
            val nr = r + dr
            val nc = c + dc
            if (nr in grid.indices && nc >= 0 && nc < grid[nr].size) {
                neighbors.add(grid[nr][nc])
            }
        }
        return neighbors
    }

    /** Calculate the Euclidean distance between two keys on the grid. */
    fun getDistance(char1: Char, char2: Char): Double {
        val norm1 = normalizeChar(char1)
        val norm2 = normalizeChar(char2)
        val pos1 = posMap[norm1] ?: return HumanTypingConfig.FAR_KEY_THRESHOLD
        val pos2 = posMap[norm2] ?: return HumanTypingConfig.FAR_KEY_THRESHOLD
        val dr = (pos1.first - pos2.first).toDouble()
        val dc = (pos1.second - pos2.second).toDouble()
        return sqrt(dr * dr + dc * dc)
    }

    /** Return a random neighboring key, preserving the original case. */
    fun getRandomNeighbor(char: Char): Char {
        val wasUpper = char.isUpperCase()
        val neighbors = getNeighborKeys(char)
        val result = if (neighbors.isEmpty()) {
            val flatGrid = grid.flatten()
            flatGrid[Random.nextInt(flatGrid.size)]
        } else {
            neighbors[Random.nextInt(neighbors.size)]
        }
        return if (wasUpper) result.uppercaseChar() else result
    }

    /** Check if a character is a direct accent (always false on QWERTY). */
    fun isDirectAccent(char: Char): Boolean = char.lowercaseChar() in directAccents

    /** Check if a character is a composed accent. */
    fun isComposedAccent(char: Char): Boolean = char.lowercaseChar() in composedAccents
}

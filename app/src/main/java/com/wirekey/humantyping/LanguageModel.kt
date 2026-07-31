package com.wirekey.humantyping

/**
 * Language model for typing difficulty estimation.
 * Ported from HumanTyping Python library (language.py).
 *
 * Provides word difficulty classification and common bigram detection,
 * which the Markov typer uses to adjust speed and error rates.
 */
object LanguageModel {

    /** Top ~100 most common English words. */
    private val COMMON_WORDS: Set<String> = setOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "it",
        "for", "not", "on", "with", "he", "as", "you", "do", "at", "this",
        "but", "his", "by", "from", "they", "we", "say", "her", "she", "or",
        "an", "will", "my", "one", "all", "would", "there", "their", "what",
        "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
        "when", "make", "can", "like", "time", "no", "just", "him", "know",
        "take", "people", "into", "year", "your", "good", "some", "could",
        "them", "see", "other", "than", "then", "now", "look", "only", "come",
        "its", "over", "think", "also", "back", "after", "use", "two", "how",
        "our", "work", "first", "well", "way", "even", "new", "want", "because"
    )

    /** Common English bigrams (two-character sequences typed in burst). */
    private val COMMON_BIGRAMS: Set<String> = setOf(
        "th", "he", "in", "er", "an", "re", "on", "at", "en", "nd", "ti", "es",
        "or", "te", "of", "ed", "is", "it", "al", "ar", "st", "to", "nt", "ng",
        "se", "ha", "as", "ou", "io", "le", "ve", "co", "me", "de", "hi", "ri",
        "ro", "ic", "ne", "ea", "ra", "ce"
    )

    /** Characters treated as punctuation for word-boundary stripping. */
    private const val PUNCTUATION_CHARS = ".,!?;:'\"-()[]{}/"

    /** Word difficulty classification. */
    enum class WordDifficulty { COMMON, COMPLEX, NORMAL }

    /**
     * Classify a word's typing difficulty.
     *
     * - **COMMON**: one of the top ~100 English words → typed faster, fewer errors
     * - **COMPLEX**: long (>8 chars) or contains difficult keys (z, x, q, j) → typed slower, more errors
     * - **NORMAL**: everything else
     */
    fun getWordDifficulty(word: String): WordDifficulty {
        val wordLower = word.lowercase().trim { it in PUNCTUATION_CHARS }
        if (wordLower in COMMON_WORDS) return WordDifficulty.COMMON
        val isLong = wordLower.length > 8
        val hasComplexChars = wordLower.any { it in "zxqj" }
        if (isLong || hasComplexChars) return WordDifficulty.COMPLEX
        return WordDifficulty.NORMAL
    }

    /** Check if two consecutive characters form a common English bigram. */
    fun isCommonBigram(char1: Char, char2: Char): Boolean {
        val bigram = "${char1}${char2}".lowercase()
        return bigram in COMMON_BIGRAMS
    }
}

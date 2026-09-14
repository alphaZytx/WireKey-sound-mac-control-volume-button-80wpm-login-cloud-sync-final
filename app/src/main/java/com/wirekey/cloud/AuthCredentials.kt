package com.wirekey.cloud

/**
 * Client-side checks on the sign-in / register form, so an obviously malformed address or
 * a too-short password never costs a network round trip. The server remains the authority:
 * everything here is a courtesy, not a security boundary.
 */
object AuthCredentials {

    /** Supabase rejects anything shorter by default (Auth → Password settings). */
    const val MIN_PASSWORD_LENGTH = 6

    /**
     * Returns a human-readable problem with [email]/[password], or null when the pair is
     * worth sending to Supabase.
     */
    fun validate(email: String, password: String): String? = when {
        email.isBlank() -> "Enter your email address"
        !isPlausibleEmail(email) -> "That does not look like an email address"
        password.isEmpty() -> "Enter your password"
        password.length < MIN_PASSWORD_LENGTH ->
            "Password must be at least $MIN_PASSWORD_LENGTH characters"
        else -> null
    }

    /**
     * A deliberately loose shape check — "something@something.something", no spaces.
     * Anything stricter starts rejecting addresses that are perfectly valid.
     */
    fun isPlausibleEmail(email: String): Boolean {
        val trimmed = email.trim()
        if (trimmed.any { it.isWhitespace() }) return false
        val at = trimmed.indexOf('@')
        if (at <= 0 || at != trimmed.lastIndexOf('@')) return false
        val domain = trimmed.substring(at + 1)
        val dot = domain.indexOf('.')
        return dot > 0 && dot < domain.length - 1
    }

    /** Emails are matched case-insensitively by Supabase; normalise before sending. */
    fun normalizeEmail(email: String): String = email.trim().lowercase()
}

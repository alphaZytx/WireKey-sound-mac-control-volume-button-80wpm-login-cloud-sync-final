package com.wirekey.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCredentialsTest {

    @Test
    fun `a well-formed pair passes`() {
        assertNull(AuthCredentials.validate("someone@example.com", "hunter2!"))
    }

    @Test
    fun `blank and malformed emails are rejected before any network call`() {
        assertNotNull(AuthCredentials.validate("", "hunter2!"))
        assertNotNull(AuthCredentials.validate("   ", "hunter2!"))
        assertNotNull(AuthCredentials.validate("someone", "hunter2!"))
        assertNotNull(AuthCredentials.validate("someone@example", "hunter2!"))
        assertNotNull(AuthCredentials.validate("some one@example.com", "hunter2!"))
    }

    /** Matches the server's own default, so the user is told locally rather than by a 400. */
    @Test
    fun `passwords shorter than the Supabase minimum are rejected`() {
        val tooShort = "x".repeat(AuthCredentials.MIN_PASSWORD_LENGTH - 1)
        assertNotNull(AuthCredentials.validate("someone@example.com", tooShort))
        assertNull(
            AuthCredentials.validate(
                "someone@example.com",
                "x".repeat(AuthCredentials.MIN_PASSWORD_LENGTH)
            )
        )
    }

    @Test
    fun `email shape check accepts real addresses and rejects broken ones`() {
        assertTrue(AuthCredentials.isPlausibleEmail("a.b+tag@sub.example.co.uk"))
        assertFalse(AuthCredentials.isPlausibleEmail("two@at@example.com"))
        assertFalse(AuthCredentials.isPlausibleEmail("@example.com"))
        assertFalse(AuthCredentials.isPlausibleEmail("someone@example."))
    }

    @Test
    fun `emails are normalised so the same account is not created twice`() {
        assertEquals("someone@example.com", AuthCredentials.normalizeEmail("  SomeOne@Example.COM "))
    }
}

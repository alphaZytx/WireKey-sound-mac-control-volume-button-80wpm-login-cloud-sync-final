package com.wirekey.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The GoTrue error strings a user is most likely to hit, turned into readable text. */
class CloudAuthRepositoryTest {

    @Test
    fun `a wrong password does not leak the API's wording`() {
        assertEquals(
            "Wrong email or password",
            CloudAuthRepository.describeAuthError(RuntimeException("Invalid login credentials"))
        )
    }

    @Test
    fun `registering an existing email points the user at sign-in`() {
        val message =
            CloudAuthRepository.describeAuthError(RuntimeException("User already registered"))
        assertTrue(message.contains("sign in", ignoreCase = true))
    }

    @Test
    fun `an unconfirmed address is explained rather than shown as a failure`() {
        val message =
            CloudAuthRepository.describeAuthError(RuntimeException("Email not confirmed"))
        assertTrue(message.contains("inbox", ignoreCase = true))
    }

    @Test
    fun `network exceptions are detected through wrapper layers`() {
        assertTrue(CloudAuthRepository.isNetworkError(UnknownHostException("nope")))
        assertTrue(CloudAuthRepository.isNetworkError(RuntimeException("x", ConnectException())))
        assertTrue(
            CloudAuthRepository.isNetworkError(
                RuntimeException("outer", RuntimeException("inner", SocketTimeoutException()))
            )
        )
        assertFalse(CloudAuthRepository.isNetworkError(IllegalStateException("not a socket")))
    }

    /** A self-referencing cause must not spin the walk forever. */
    @Test
    fun `cause chains that loop terminate`() {
        val looping = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertFalse(CloudAuthRepository.isNetworkError(looping))
    }

    @Test
    fun `an unrecognised failure still says something`() {
        val message = CloudAuthRepository.describeAuthError(RuntimeException(""))
        assertTrue(message.isNotBlank())
    }
}

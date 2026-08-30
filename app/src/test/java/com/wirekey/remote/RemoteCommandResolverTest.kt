package com.wirekey.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCommandResolverTest {

    @Test
    fun `idle with a compose screen starts a send`() {
        assertEquals(
            RemoteAction.START,
            RemoteCommandResolver.resolve(isSending = false, isPaused = false, hasStartHandler = true)
        )
    }

    @Test
    fun `idle without a compose screen is rejected rather than silently dropped`() {
        assertEquals(
            RemoteAction.IGNORED_NO_SCREEN,
            RemoteCommandResolver.resolve(isSending = false, isPaused = false, hasStartHandler = false)
        )
    }

    @Test
    fun `sending pauses`() {
        assertEquals(
            RemoteAction.PAUSE,
            RemoteCommandResolver.resolve(isSending = true, isPaused = false, hasStartHandler = true)
        )
    }

    @Test
    fun `paused resumes`() {
        assertEquals(
            RemoteAction.RESUME,
            RemoteCommandResolver.resolve(isSending = true, isPaused = true, hasStartHandler = true)
        )
    }

    /**
     * The whole point of checking send state before handler presence: a send started from
     * Compose Mode has to stay controllable after navigating to Live Mode or Pairing,
     * where no start handler is registered.
     */
    @Test
    fun `pause and resume do not need a compose screen`() {
        assertEquals(
            RemoteAction.PAUSE,
            RemoteCommandResolver.resolve(isSending = true, isPaused = false, hasStartHandler = false)
        )
        assertEquals(
            RemoteAction.RESUME,
            RemoteCommandResolver.resolve(isSending = true, isPaused = true, hasStartHandler = false)
        )
    }

    /**
     * `!isSending && isPaused` should never occur -- TypingSessionManager clears both
     * together -- but a transient read must still resolve cleanly rather than throw.
     */
    @Test
    fun `impossible paused-while-idle state still resolves`() {
        assertEquals(
            RemoteAction.START,
            RemoteCommandResolver.resolve(isSending = false, isPaused = true, hasStartHandler = true)
        )
        assertEquals(
            RemoteAction.IGNORED_NO_SCREEN,
            RemoteCommandResolver.resolve(isSending = false, isPaused = true, hasStartHandler = false)
        )
    }
}

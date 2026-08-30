package com.wirekey.remote

/**
 * The whole Start / Pause / Resume decision, as one pure function.
 *
 * Kept free of Android types so every path is covered by fast JVM tests.
 */
object RemoteCommandResolver {

    /**
     * @param hasStartHandler whether a Compose Mode screen is currently alive to
     *   supply the draft text. Pause and Resume never need it -- they act on the
     *   app-scoped TypingSessionManager -- which is why they are checked first: a send
     *   started from Compose Mode stays controllable even after you navigate away.
     */
    fun resolve(
        isSending: Boolean,
        isPaused: Boolean,
        hasStartHandler: Boolean
    ): RemoteAction = when {
        isSending && isPaused -> RemoteAction.RESUME
        isSending -> RemoteAction.PAUSE
        !hasStartHandler -> RemoteAction.IGNORED_NO_SCREEN
        else -> RemoteAction.START
    }
}

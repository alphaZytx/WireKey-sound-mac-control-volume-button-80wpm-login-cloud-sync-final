package com.wirekey.cloud

import android.util.Log
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.SignOutScope
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Email/password sign-in, registration and sign-out, plus the one piece of state the rest of
 * the app cares about: [authState].
 *
 * Session persistence is not implemented here — supabase-kt's Android artifact writes the
 * session to SharedPreferences and reloads it on startup (see [SupabaseCloudClient]), which
 * is what makes the user stay signed in across launches. This class only *observes* that.
 */
class CloudAuthRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _authState = MutableStateFlow<CloudAuthState>(
        if (SupabaseConfig.isConfigured) CloudAuthState.Loading else CloudAuthState.NotConfigured
    )
    val authState: StateFlow<CloudAuthState> = _authState.asStateFlow()

    init {
        if (SupabaseConfig.isConfigured) {
            scope.launch {
                val client = SupabaseCloudClient.get()
                if (client == null) {
                    _authState.value = CloudAuthState.NotConfigured
                    return@launch
                }
                // Never completes: this is the app's live view of the session, including the
                // restore-from-storage at launch and every background token refresh after.
                client.auth.sessionStatus.collect { status ->
                    _authState.value = status.toCloudAuthState()
                }
            }
        }
    }

    /** The signed-in user's Supabase id, or null. This — never the email — owns cloud rows. */
    fun currentUserId(): String? = (_authState.value as? CloudAuthState.SignedIn)?.userId

    suspend fun signIn(email: String, password: String): AuthResult {
        val client = SupabaseCloudClient.get() ?: return notConfigured()
        return try {
            client.auth.signInWith(Email) {
                this.email = AuthCredentials.normalizeEmail(email)
                this.password = password
            }
            AuthResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sign-in failed", e)
            AuthResult.Failure(describeAuthError(e))
        }
    }

    /**
     * Creates the account. When the Supabase project has "Confirm email" switched on, no
     * session is established until the user clicks the emailed link — that is reported as
     * [AuthResult.ConfirmationRequired] rather than being mistaken for a silent failure.
     */
    suspend fun register(email: String, password: String): AuthResult {
        val client = SupabaseCloudClient.get() ?: return notConfigured()
        return try {
            client.auth.signUpWith(Email) {
                this.email = AuthCredentials.normalizeEmail(email)
                this.password = password
            }
            // Asking the client whether a session actually exists is more reliable than
            // interpreting the return value, which differs between confirmation settings.
            if (client.auth.currentSessionOrNull() != null) {
                AuthResult.Success
            } else {
                AuthResult.ConfirmationRequired
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Registration failed", e)
            AuthResult.Failure(describeAuthError(e))
        }
    }

    suspend fun signOut(): AuthResult {
        val client = SupabaseCloudClient.get() ?: return notConfigured()
        return try {
            // LOCAL, explicitly: a GLOBAL sign-out revokes the refresh token everywhere, which
            // in a feature built around several phones sharing an account would sign the user
            // out of all the *other* phones too.
            client.auth.signOut(SignOutScope.LOCAL)
            AuthResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sign-out failed", e)
            // The local session is dropped either way by supabase-kt, so the user is not
            // left stuck signed in because the server could not be reached.
            AuthResult.Failure(describeAuthError(e))
        }
    }

    private fun notConfigured() = AuthResult.Failure("Cloud sync is not configured in this build")

    companion object {
        private const val TAG = "CloudAuth"

        /**
         * Turns Ktor/GoTrue exceptions into something worth showing on a phone screen.
         * The raw messages are either JSON or stack-trace shaped.
         */
        fun describeAuthError(e: Exception): String {
            val raw = e.message.orEmpty()
            return when {
                raw.contains("Invalid login credentials", ignoreCase = true) ->
                    "Wrong email or password"
                raw.contains("Email not confirmed", ignoreCase = true) ->
                    "Confirm your email address first — check your inbox"
                raw.contains("User already registered", ignoreCase = true) ||
                    raw.contains("already been registered", ignoreCase = true) ->
                    "That email already has an account — sign in instead"
                raw.contains("Password should be", ignoreCase = true) ->
                    "Password is too weak"
                raw.contains("over_email_send_rate_limit", ignoreCase = true) ||
                    raw.contains("rate limit", ignoreCase = true) ->
                    "Too many attempts — wait a minute and try again"
                isNetworkError(e) -> "No connection to Supabase"
                raw.isNotBlank() -> raw.take(160)
                else -> "Something went wrong (${e::class.java.simpleName})"
            }
        }

        /**
         * Matched on type *names* rather than types: the concrete exception comes from Ktor's
         * engine layer, and importing those would tie this file to the engine in use.
         */
        fun isNetworkError(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                val name = cause::class.java.name
                if (name.contains("UnknownHost") || name.contains("ConnectException") ||
                    name.contains("SocketTimeout") || name.contains("SocketException") ||
                    name.contains("HttpRequestTimeout") || name.contains("SSLException")
                ) {
                    return true
                }
                cause = cause.cause.takeIf { it !== cause }
            }
            return false
        }
    }
}

/** supabase-kt's session model, reduced to the four states the UI distinguishes. */
private fun SessionStatus.toCloudAuthState(): CloudAuthState = when (this) {
    is SessionStatus.Authenticated -> {
        val user = session.user
        if (user == null) CloudAuthState.SignedOut else CloudAuthState.SignedIn(user.id, user.email)
    }
    is SessionStatus.NotAuthenticated -> CloudAuthState.SignedOut
    // A stored session is being read back, or the token could not be refreshed because the
    // phone is offline. Both are transient and supabase-kt retries on its own, so neither
    // is reported as signed out — that would wrongly send the user back to the login screen.
    is SessionStatus.LoadingFromStorage -> CloudAuthState.Loading
    is SessionStatus.NetworkError -> CloudAuthState.Loading
}

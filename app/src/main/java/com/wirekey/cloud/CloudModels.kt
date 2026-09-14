package com.wirekey.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The user's single cloud-saved Compose Mode document, as stored in `public.compose_documents`.
 *
 * Ownership is the Supabase *user id* (a uuid), not the email address: emails can be changed
 * by the user, and the RLS policies on the table compare against `auth.uid()`.
 */
@Serializable
data class ComposeDocument(
    @SerialName(SupabaseConfig.COLUMN_USER_ID) val userId: String,
    @SerialName(SupabaseConfig.COLUMN_CONTENT) val content: String = "",
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * What a Sync writes. Deliberately narrower than [ComposeDocument]: `updated_at` is set by a
 * database trigger, so the client never claims a timestamp of its own.
 */
@Serializable
data class ComposeDocumentUpsert(
    @SerialName(SupabaseConfig.COLUMN_USER_ID) val userId: String,
    @SerialName(SupabaseConfig.COLUMN_CONTENT) val content: String
)

/** Who, if anyone, is signed in. Mirrors the shape of HidConnectionState for familiarity. */
sealed class CloudAuthState {
    /** No Supabase credentials were compiled in — the cloud feature is absent, not broken. */
    object NotConfigured : CloudAuthState()

    /** Restoring a persisted session at startup, or a sign-in/register call is in flight. */
    object Loading : CloudAuthState()

    object SignedOut : CloudAuthState()

    data class SignedIn(val userId: String, val email: String?) : CloudAuthState()
}

/** The small status line Compose Mode shows next to its Sync button. */
sealed class SyncStatus {
    object NotConfigured : SyncStatus()
    object SignedOut : SyncStatus()

    /** Signed in; fetching the cloud document or (re)opening the realtime channel. */
    object Connecting : SyncStatus()

    /** A push or a pull is in flight. Renders as "Syncing…". */
    object Syncing : SyncStatus()

    /** Local editor and cloud document agree as of [atMillis]. Renders as "Synced". */
    data class Synced(val atMillis: Long) : SyncStatus()

    /** Renders as "Sync failed"; [message] is shown underneath. */
    data class Failed(val message: String) : SyncStatus()
}

/** Result of a sign-in / register attempt, so the UI can show a message without knowing Ktor. */
sealed class AuthResult {
    object Success : AuthResult()

    /** Register succeeded but the project requires email confirmation before signing in. */
    object ConfirmationRequired : AuthResult()

    data class Failure(val message: String) : AuthResult()
}

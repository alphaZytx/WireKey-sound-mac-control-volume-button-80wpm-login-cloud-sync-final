package com.wirekey.cloud

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.HasRecord
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps one Compose Mode document per Supabase account in step across every phone signed
 * into that account.
 *
 * The model is deliberately the simplest one that satisfies "the latest Sync wins":
 *
 *  - **Push** ([push]) replaces the whole cloud document with the phone's editor contents.
 *    No merge, no diff, no version check.
 *  - **Pull** happens on sign-in and then continuously over Supabase Realtime. Every change
 *    to the row is emitted on [cloudText], and Compose Mode replaces its editor with it.
 *
 * App-scoped rather than screen-scoped on purpose: the realtime channel stays open while the
 * user is on other screens, so re-entering Compose Mode shows text that is already current
 * (the [cloudText] replay cache) instead of starting a fetch.
 *
 * Nothing here touches Bluetooth, the HID layer or the typing engine — a sync in flight and a
 * send in flight are independent, and either can happen without the other.
 */
class ComposeSyncCoordinator(
    private val authRepository: CloudAuthRepository,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _syncStatus = MutableStateFlow<SyncStatus>(
        if (SupabaseConfig.isConfigured) SyncStatus.SignedOut else SyncStatus.NotConfigured
    )
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /**
     * Every version of the cloud document this app has seen, newest last.
     *
     * `replay = 1` is what makes a freshly opened Compose Mode screen — or one reopened after
     * the user wandered off to Settings — immediately receive the current cloud text without
     * a round trip. A SharedFlow rather than a StateFlow because a StateFlow would swallow a
     * repeat of the same text, and re-syncing identical text from another phone still has to
     * count as a sync event.
     */
    private val _cloudText = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val cloudText: SharedFlow<String> = _cloudText.asSharedFlow()

    /** The live session: initial fetch + realtime subscription. Cancelled on sign-out. */
    private var sessionJob: Job? = null

    @Volatile
    private var currentUserId: String? = null

    init {
        if (SupabaseConfig.isConfigured) {
            scope.launch {
                authRepository.authState.collect { onAuthState(it) }
            }
        }
    }

    // resetReplayCache() is the only way to guarantee one account's text is never replayed
    // into the next account's editor; the opt-in is scoped to exactly that call.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun onAuthState(state: CloudAuthState) {
        when (state) {
            is CloudAuthState.NotConfigured -> {
                endSession()
                _syncStatus.value = SyncStatus.NotConfigured
            }
            is CloudAuthState.Loading -> {
                _syncStatus.value = SyncStatus.Connecting
            }
            is CloudAuthState.SignedOut -> {
                endSession()
                // The next account to sign in must not inherit this one's text, so the
                // replay cache is dropped along with the session.
                _cloudText.resetReplayCache()
                _syncStatus.value = SyncStatus.SignedOut
            }
            is CloudAuthState.SignedIn -> {
                if (currentUserId == state.userId && sessionJob?.isActive == true) return
                endSession()
                currentUserId = state.userId
                _syncStatus.value = SyncStatus.Connecting
                sessionJob = scope.launch { runSession(state.userId) }
            }
        }
    }

    private fun endSession() {
        sessionJob?.cancel()
        sessionJob = null
        currentUserId = null
    }

    /**
     * Subscribes to the user's row and loads its current contents, then stays alive until the
     * session is cancelled. Realtime reconnection is supabase-kt's job; this loop only has to
     * survive it.
     */
    private suspend fun runSession(userId: String) {
        val client = SupabaseCloudClient.get() ?: run {
            _syncStatus.value = SyncStatus.NotConfigured
            return
        }

        val channel = client.channel(channelName(userId))
        try {
            // coroutineScope, so the collector below is a *child* of this session and dies
            // with it. Launched on the outer scope it would outlive a sign-out and keep a
            // callback registered on a channel that has already been removed.
            coroutineScope {
                val changes = channel.postgresChangeFlow<PostgresAction>(schema = SCHEMA) {
                    table = SupabaseConfig.COMPOSE_TABLE
                    // Server-side filter. Belt to RLS's braces: the policies already make other
                    // users' rows unreadable, and this keeps us from being woken for them at all.
                    filter(SupabaseConfig.COLUMN_USER_ID, FilterOperator.EQ, userId)
                }

                // UNDISPATCHED so the flow's callback is registered *synchronously*, before
                // subscribe() below builds the join payload. Started with a normal dispatch,
                // the registration could land after the join and the channel would carry no
                // postgres_changes binding at all — a silent, permanent loss of remote updates.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    changes.collect { action -> onRemoteChange(action) }
                }

                channel.subscribe(blockUntilSubscribed = true)

                // Only now, with the subscription live, is it safe to read the current value:
                // anything written between this read and the subscription would otherwise be
                // missed by both paths.
                loadInitialDocument(client, userId)

                // The collector never completes on its own, so this keeps the session — and
                // the channel — alive until sign-out cancels it.
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Realtime session failed", e)
            _syncStatus.value = SyncStatus.Failed(describeSyncError(e))
        } finally {
            withContext(NonCancellable) {
                runCatching { client.realtime.removeChannel(channel) }
                    .onFailure { Log.w(TAG, "Could not close realtime channel", it) }
            }
        }
    }

    /**
     * Fetches the user's document, creating an empty one the first time they sign in so that
     * every later Sync is a plain update of an existing row.
     */
    private suspend fun loadInitialDocument(client: SupabaseClient, userId: String) {
        _syncStatus.value = SyncStatus.Syncing
        try {
            val existing = client.postgrest.from(SupabaseConfig.COMPOSE_TABLE)
                .select {
                    filter { eq(SupabaseConfig.COLUMN_USER_ID, userId) }
                    limit(1)
                }
                .decodeSingleOrNull<ComposeDocument>()

            val content = existing?.content ?: run {
                writeDocument(client, userId, "")
                ""
            }
            _cloudText.emit(content)
            _syncStatus.value = SyncStatus.Synced(clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Initial document load failed", e)
            _syncStatus.value = SyncStatus.Failed(describeSyncError(e))
        }
    }

    /**
     * Replaces the cloud document with [text]. This is the "Sync" button.
     *
     * The pushed text is not fed back into [cloudText] here — the realtime echo of our own
     * write does that, which keeps every phone, including this one, converging on exactly
     * what the database holds.
     */
    suspend fun push(text: String): Boolean {
        val userId = currentUserId ?: run {
            _syncStatus.value = SyncStatus.SignedOut
            return false
        }
        val client = SupabaseCloudClient.get() ?: run {
            _syncStatus.value = SyncStatus.NotConfigured
            return false
        }

        _syncStatus.value = SyncStatus.Syncing
        return try {
            writeDocument(client, userId, text)
            _syncStatus.value = SyncStatus.Synced(clock())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync push failed", e)
            _syncStatus.value = SyncStatus.Failed(describeSyncError(e))
            false
        }
    }

    /**
     * One row per user, keyed by user id, so this single upsert covers both "first ever sync"
     * and "replace what is there". `user_id` is sent from the *session*, and the RLS policies
     * reject it if it is anyone else's.
     */
    private suspend fun writeDocument(client: SupabaseClient, userId: String, text: String) {
        client.postgrest.from(SupabaseConfig.COMPOSE_TABLE).upsert(
            value = ComposeDocumentUpsert(userId = userId, content = text),
            onConflict = SupabaseConfig.COLUMN_USER_ID
        )
    }

    private suspend fun onRemoteChange(action: PostgresAction) {
        // Insert and Update both carry the new row; Delete carries only the old one and is
        // ignored, because wiping a user's editor over a row disappearing server-side is a
        // much worse failure than briefly holding text the cloud no longer has.
        val record = (action as? HasRecord)?.record ?: return
        val content = contentFromRecord(record) ?: return
        _cloudText.emit(content)
        _syncStatus.value = SyncStatus.Synced(clock())
    }

    private fun channelName(userId: String) = "compose-sync-$userId"

    companion object {
        private const val TAG = "ComposeSync"
        private const val SCHEMA = "public"

        /**
         * Reads the document body out of a realtime row payload.
         *
         * `contentOrNull` rather than `content`: a JSON null would otherwise decode to the
         * four-character string "null" and be typed into the editor as if the user had
         * written it.
         */
        fun contentFromRecord(record: JsonObject): String? =
            runCatching {
                record[SupabaseConfig.COLUMN_CONTENT]?.jsonPrimitive?.contentOrNull
            }.getOrNull()

        fun describeSyncError(e: Exception): String = when {
            CloudAuthRepository.isNetworkError(e) -> "No connection to Supabase"
            e.message?.contains("JWT", ignoreCase = true) == true ||
                e.message?.contains("401") == true -> "Session expired — sign in again"
            e.message?.contains("row-level security", ignoreCase = true) == true ->
                "Server rejected the write (row-level security)"
            !e.message.isNullOrBlank() -> e.message!!.take(160)
            else -> "Sync failed (${e::class.java.simpleName})"
        }
    }
}

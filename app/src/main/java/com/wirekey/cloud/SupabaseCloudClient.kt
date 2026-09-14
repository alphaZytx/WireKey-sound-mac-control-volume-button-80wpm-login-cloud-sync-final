package com.wirekey.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single Supabase client for the whole app, built on first use and never rebuilt.
 *
 * It is deliberately *not* a WireKeyApp singleton constructed in onCreate: standing up the
 * Ktor/OkHttp stack takes long enough to be worth keeping off the main thread at launch,
 * so callers go through [get], which builds it on an IO dispatcher. `by lazy` is
 * synchronized, so concurrent first calls still produce exactly one client.
 *
 * Returns null when no Supabase project is configured — every caller treats that as
 * "cloud sync does not exist", never as an error.
 */
object SupabaseCloudClient {

    private val lazyClient: SupabaseClient? by lazy {
        if (!SupabaseConfig.isConfigured) return@lazy null

        createSupabaseClient(
            supabaseUrl = SupabaseConfig.url,
            supabaseKey = SupabaseConfig.anonKey
        ) {
            install(Auth) {
                // Both are already the Android defaults; pinned explicitly because the
                // "stay logged in between app launches" requirement rests on them. The
                // session is persisted by the Android artifact in SharedPreferences and
                // reloaded here, and the access token is refreshed in the background so a
                // phone left closed overnight still wakes up authenticated.
                autoLoadFromStorage = true
                autoSaveToStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
            install(Realtime)
        }
    }

    suspend fun get(): SupabaseClient? = withContext(Dispatchers.IO) { lazyClient }
}

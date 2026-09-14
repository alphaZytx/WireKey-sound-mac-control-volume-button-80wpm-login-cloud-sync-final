package com.wirekey.cloud

import com.wirekey.BuildConfig

/**
 * Where the Supabase project lives, and whether there is one at all.
 *
 * Both values are injected at build time from local.properties (see app/build.gradle.kts),
 * never checked in. Only the *anon / publishable* key is ever placed here — it is designed
 * to ship inside client apps, and every read and write it can make is still fenced in by
 * Row Level Security on the server. A service-role key would bypass RLS entirely and must
 * never appear in the APK.
 *
 * [isConfigured] is false on a checkout with no credentials, and the whole cloud feature —
 * client, auth, sync, UI — stays switched off in that case, so WireKey keeps working purely
 * as a Bluetooth keyboard.
 */
object SupabaseConfig {
    val url: String = BuildConfig.SUPABASE_URL.trim()
    val anonKey: String = BuildConfig.SUPABASE_ANON_KEY.trim()

    val isConfigured: Boolean = url.isNotEmpty() && anonKey.isNotEmpty()

    /** One row per user, primary-keyed by the authenticated Supabase user id. */
    const val COMPOSE_TABLE = "compose_documents"

    /** Column names, kept in one place because both PostgREST and Realtime filters use them. */
    const val COLUMN_USER_ID = "user_id"
    const val COLUMN_CONTENT = "content"
}

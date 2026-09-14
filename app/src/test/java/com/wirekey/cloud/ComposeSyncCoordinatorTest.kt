package com.wirekey.cloud

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/**
 * Covers the parts of the sync path that are pure: reading a realtime row payload, and the
 * error text the status line ends up showing. The realtime channel itself needs a live
 * project and is exercised by hand.
 */
class ComposeSyncCoordinatorTest {

    @Test
    fun `document body is read out of a realtime record`() {
        val record = buildJsonObject {
            put(SupabaseConfig.COLUMN_USER_ID, "8b1f-uuid")
            put(SupabaseConfig.COLUMN_CONTENT, "hello from another phone")
        }
        assertEquals(
            "hello from another phone",
            ComposeSyncCoordinator.contentFromRecord(record)
        )
    }

    /** An empty document is a real state — the user cleared it and synced — not a missing one. */
    @Test
    fun `empty content survives as empty rather than becoming null`() {
        val record = buildJsonObject { put(SupabaseConfig.COLUMN_CONTENT, "") }
        assertEquals("", ComposeSyncCoordinator.contentFromRecord(record))
    }

    @Test
    fun `a record with no usable content yields null so the editor is left alone`() {
        assertNull(ComposeSyncCoordinator.contentFromRecord(buildJsonObject { }))
        assertNull(
            ComposeSyncCoordinator.contentFromRecord(
                buildJsonObject { put(SupabaseConfig.COLUMN_CONTENT, JsonNull) }
            )
        )
    }

    @Test
    fun `newlines and quotes round-trip, since Compose Mode is mostly code`() {
        val code = "fun main() {\n    println(\"hi\")\n}\n"
        val record = buildJsonObject { put(SupabaseConfig.COLUMN_CONTENT, code) }
        assertEquals(code, ComposeSyncCoordinator.contentFromRecord(record))
    }

    @Test
    fun `network failures are reported as connectivity, not as a raw exception`() {
        val message = ComposeSyncCoordinator.describeSyncError(
            RuntimeException("wrapped", UnknownHostException("db.supabase.co"))
        )
        assertEquals("No connection to Supabase", message)
    }

    @Test
    fun `an expired token tells the user what to do about it`() {
        val message = ComposeSyncCoordinator.describeSyncError(
            RuntimeException("JWT expired")
        )
        assertTrue(message.contains("sign in", ignoreCase = true))
    }
}

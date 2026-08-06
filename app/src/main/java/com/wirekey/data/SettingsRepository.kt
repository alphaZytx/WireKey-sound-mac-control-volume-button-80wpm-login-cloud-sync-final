package com.wirekey.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** A previously-sent Compose Mode message, newest first in [SettingsRepository.sendHistory]. */
data class SentMessage(val text: String, val timestampMillis: Long)

class SettingsRepository(private val context: Context) {
    companion object {
        val TARGET_WPM = intPreferencesKey("target_wpm")
        val HUMAN_TYPING_ENABLED = booleanPreferencesKey("human_typing_enabled")
        val NATURAL_TYPING_ENABLED = booleanPreferencesKey("natural_typing_enabled")
        val CODE_EDITOR_MODE = booleanPreferencesKey("code_editor_mode")
        val APP_THEME = stringPreferencesKey("app_theme")

        // Send history is stored in a fixed number of indexed slots (rather than one
        // serialized blob) so reads/writes never need custom parsing/escaping of
        // arbitrary user text, and the stored size is always bounded.
        private const val MAX_HISTORY = 15
        private val HISTORY_COUNT = intPreferencesKey("history_count")
        private fun historyTextKey(index: Int) = stringPreferencesKey("history_text_$index")
        private fun historyTimeKey(index: Int) = longPreferencesKey("history_time_$index")
    }

    val targetWpm: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[TARGET_WPM] ?: 60
    }

    val humanTypingEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HUMAN_TYPING_ENABLED] ?: true
    }

    val naturalTypingEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[NATURAL_TYPING_ENABLED] ?: true
    }

    val appTheme: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[APP_THEME] ?: "system"
    }

    val codeEditorMode: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[CODE_EDITOR_MODE] ?: true
    }

    val sendHistory: Flow<List<SentMessage>> = context.dataStore.data.map { preferences ->
        val count = (preferences[HISTORY_COUNT] ?: 0).coerceIn(0, MAX_HISTORY)
        (0 until count).mapNotNull { index ->
            val text = preferences[historyTextKey(index)] ?: return@mapNotNull null
            val timestamp = preferences[historyTimeKey(index)] ?: 0L
            SentMessage(text, timestamp)
        }
    }

    suspend fun setTargetWpm(wpm: Int) {
        context.dataStore.edit { preferences ->
            preferences[TARGET_WPM] = wpm.coerceIn(20, 150)
        }
    }

    suspend fun setHumanTypingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HUMAN_TYPING_ENABLED] = enabled
        }
    }

    suspend fun setNaturalTypingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NATURAL_TYPING_ENABLED] = enabled
        }
    }

    suspend fun setAppTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[APP_THEME] = theme
        }
    }

    suspend fun setCodeEditorMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CODE_EDITOR_MODE] = enabled
        }
    }

    /**
     * Records [text] as the newest history entry. If [text] already exists elsewhere in
     * the history, the older copy is dropped so it moves to the front instead of duplicating.
     * The list is capped at [MAX_HISTORY]; the oldest entry is evicted once full.
     * Blank text is ignored — there's nothing meaningful to resend.
     */
    suspend fun addToHistory(text: String) {
        if (text.isBlank()) return

        context.dataStore.edit { preferences ->
            val count = (preferences[HISTORY_COUNT] ?: 0).coerceIn(0, MAX_HISTORY)
            val existingTexts = (0 until count).map { preferences[historyTextKey(it)] ?: "" }
            val existingTimes = (0 until count).map { preferences[historyTimeKey(it)] ?: 0L }

            val newTexts = mutableListOf(text)
            val newTimes = mutableListOf(System.currentTimeMillis())
            for (i in existingTexts.indices) {
                if (existingTexts[i] == text) continue
                newTexts.add(existingTexts[i])
                newTimes.add(existingTimes[i])
            }

            val finalCount = newTexts.size.coerceAtMost(MAX_HISTORY)
            preferences[HISTORY_COUNT] = finalCount
            for (i in 0 until finalCount) {
                preferences[historyTextKey(i)] = newTexts[i]
                preferences[historyTimeKey(i)] = newTimes[i]
            }
            // Clear any slots left over from a larger previous list.
            for (i in finalCount until MAX_HISTORY) {
                preferences.remove(historyTextKey(i))
                preferences.remove(historyTimeKey(i))
            }
        }
    }

    /** Removes the history entry at [index] (as ordered in [sendHistory]). Out-of-range indices are ignored. */
    suspend fun removeHistoryEntry(index: Int) {
        context.dataStore.edit { preferences ->
            val count = (preferences[HISTORY_COUNT] ?: 0).coerceIn(0, MAX_HISTORY)
            if (index !in 0 until count) return@edit

            val texts = (0 until count).map { preferences[historyTextKey(it)] ?: "" }.toMutableList()
            val times = (0 until count).map { preferences[historyTimeKey(it)] ?: 0L }.toMutableList()
            texts.removeAt(index)
            times.removeAt(index)

            val newCount = texts.size
            preferences[HISTORY_COUNT] = newCount
            for (i in 0 until newCount) {
                preferences[historyTextKey(i)] = texts[i]
                preferences[historyTimeKey(i)] = times[i]
            }
            for (i in newCount until MAX_HISTORY) {
                preferences.remove(historyTextKey(i))
                preferences.remove(historyTimeKey(i))
            }
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { preferences ->
            for (i in 0 until MAX_HISTORY) {
                preferences.remove(historyTextKey(i))
                preferences.remove(historyTimeKey(i))
            }
            preferences.remove(HISTORY_COUNT)
        }
    }
}

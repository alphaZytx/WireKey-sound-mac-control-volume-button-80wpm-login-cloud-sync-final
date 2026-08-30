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
import com.wirekey.remote.LockKey
import com.wirekey.remote.RemoteControlSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
        val ACOUSTIC_FEEDBACK_ENABLED = booleanPreferencesKey("acoustic_feedback_enabled")
        val SOUND_PACK_ID = stringPreferencesKey("sound_pack_id")

        // ── Mac Remote Control ────────────────────────────────────────────────
        // All default to today's behaviour: the master switch is off, so nothing
        // about the app changes until the user opts in.
        val REMOTE_CONTROL_ENABLED = booleanPreferencesKey("remote_control_enabled")
        val REMOTE_TRIGGER_KEY = stringPreferencesKey("remote_trigger_key")
        val REMOTE_GESTURE_WINDOW_MS = intPreferencesKey("remote_gesture_window_ms")
        val REMOTE_HAPTICS_ENABLED = booleanPreferencesKey("remote_haptics_enabled")

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

    // These two back the Acoustic Typing Feedback Engine, which reloads its SoundPool
    // assets whenever soundPackId changes -- distinctUntilChanged() keeps that reload
    // from firing on every unrelated DataStore write (WPM, theme, history, ...), since
    // context.dataStore.data re-emits its whole snapshot on any key changing.
    val acousticFeedbackEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[ACOUSTIC_FEEDBACK_ENABLED] ?: true
    }.distinctUntilChanged()

    val soundPackId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SOUND_PACK_ID] ?: "macbook-real"
    }.distinctUntilChanged()

    // These four feed RemoteControlCoordinator's cached settings snapshot. Like the
    // acoustic pair above they are distinctUntilChanged(), because dataStore.data
    // re-emits the whole snapshot on any key changing -- without it, every WPM or
    // history write would needlessly reconfigure the gesture detector.
    val remoteControlEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_CONTROL_ENABLED] ?: false
    }.distinctUntilChanged()

    val remoteTriggerKey: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_TRIGGER_KEY] ?: LockKey.DEFAULT.id
    }.distinctUntilChanged()

    val remoteGestureWindowMs: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_GESTURE_WINDOW_MS] ?: RemoteControlSettings.DEFAULT_WINDOW_MS
    }.distinctUntilChanged()

    val remoteHapticsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[REMOTE_HAPTICS_ENABLED] ?: true
    }.distinctUntilChanged()

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

    suspend fun setAcousticFeedbackEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ACOUSTIC_FEEDBACK_ENABLED] = enabled
        }
    }

    suspend fun setSoundPackId(packId: String) {
        context.dataStore.edit { preferences ->
            preferences[SOUND_PACK_ID] = packId
        }
    }

    suspend fun setRemoteControlEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REMOTE_CONTROL_ENABLED] = enabled
        }
    }

    suspend fun setRemoteTriggerKey(key: LockKey) {
        context.dataStore.edit { preferences ->
            preferences[REMOTE_TRIGGER_KEY] = key.id
        }
    }

    suspend fun setRemoteGestureWindowMs(windowMs: Int) {
        context.dataStore.edit { preferences ->
            preferences[REMOTE_GESTURE_WINDOW_MS] = RemoteControlSettings.coerceWindow(windowMs)
        }
    }

    suspend fun setRemoteHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REMOTE_HAPTICS_ENABLED] = enabled
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

package com.wirekey.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val TARGET_WPM = intPreferencesKey("target_wpm")
        val HUMAN_TYPING_ENABLED = booleanPreferencesKey("human_typing_enabled")
        val NATURAL_TYPING_ENABLED = booleanPreferencesKey("natural_typing_enabled")
        val CODE_EDITOR_MODE = booleanPreferencesKey("code_editor_mode")
        val APP_THEME = stringPreferencesKey("app_theme")
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
}

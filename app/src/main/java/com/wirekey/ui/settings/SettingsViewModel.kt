package com.wirekey.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wirekey.WireKeyApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val repository = WireKeyApp.settingsRepository

    val targetWpm = repository.targetWpm.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 60
    )
    val humanTypingEnabled = repository.humanTypingEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val naturalTypingEnabled = repository.naturalTypingEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val appTheme = repository.appTheme.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "system"
    )
    val codeEditorMode = repository.codeEditorMode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    fun setTargetWpm(wpm: Int) {
        viewModelScope.launch { repository.setTargetWpm(wpm) }
    }

    fun setHumanTypingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setHumanTypingEnabled(enabled) }
    }

    fun setNaturalTypingEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setNaturalTypingEnabled(enabled) }
    }

    fun setAppTheme(theme: String) {
        viewModelScope.launch { repository.setAppTheme(theme) }
    }

    fun setCodeEditorMode(enabled: Boolean) {
        viewModelScope.launch { repository.setCodeEditorMode(enabled) }
    }
}

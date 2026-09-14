package com.wirekey.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wirekey.WireKeyApp
import com.wirekey.audio.SoundPackCatalog
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
    val acousticFeedbackEnabled = repository.acousticFeedbackEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val soundPackId = repository.soundPackId.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), SoundPackCatalog.DEFAULT_PACK_ID
    )
    val availableSoundPacks = SoundPackCatalog.ALL_PACKS

    // ── Mac Remote Control ───────────────────────────────────────────────────
    private val remoteCoordinator = WireKeyApp.remoteControlCoordinator

    val remoteControlEnabled = repository.remoteControlEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val remoteHapticsEnabled = repository.remoteHapticsEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val remoteBleState = WireKeyApp.bleRemoteControlServer.state

    /** Live diagnostics, so the user can confirm their Mac sends LED reports at all. */
    val remoteReportLog = remoteCoordinator.reportLog
    val remoteReportCount = remoteCoordinator.reportCount
    val remoteHostActivityCount = remoteCoordinator.hostActivityCount
    val remoteLastNotice = remoteCoordinator.lastNotice

    // ── Cloud Sync ───────────────────────────────────────────────────────────
    // Read-only here: the Settings row only reports who is signed in and what sync is doing,
    // and hands off to the account screen for anything that changes state.
    val cloudAuthState = WireKeyApp.cloudAuthRepository.authState
    val cloudSyncStatus = WireKeyApp.composeSyncCoordinator.syncStatus

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

    fun setAcousticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAcousticFeedbackEnabled(enabled) }
    }

    fun setSoundPackId(packId: String) {
        viewModelScope.launch { repository.setSoundPackId(packId) }
    }

    fun setRemoteControlEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setRemoteControlEnabled(enabled) }
    }

    fun setRemoteHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setRemoteHapticsEnabled(enabled) }
    }

    fun clearRemoteDiagnostics() {
        remoteCoordinator.clearDiagnostics()
    }

}

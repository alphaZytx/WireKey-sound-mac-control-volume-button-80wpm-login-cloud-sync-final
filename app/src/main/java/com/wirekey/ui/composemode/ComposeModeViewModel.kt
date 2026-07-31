package com.wirekey.ui.composemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wirekey.WireKeyApp
import com.wirekey.util.CadenceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ComposeModeViewModel : ViewModel() {
    private val controller = WireKeyApp.hidKeyboardController
    private val settingsRepo = WireKeyApp.settingsRepository

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    val connectionState = controller.connectionState
    private val typingManager = WireKeyApp.typingSessionManager

    val isSending = typingManager.isSending
    val isPaused = typingManager.isPaused
    val sendProgress = typingManager.sendProgress
    val remainingTimeMs = typingManager.remainingTimeMs
    val sendCompleteEvent = typingManager.sendCompleteEvent
    val dynamicWpm = typingManager.dynamicWpm

    // Observe settings from DataStore dynamically
    val cadenceSettings: StateFlow<CadenceSettings> = combine(
        settingsRepo.targetWpm,
        settingsRepo.humanTypingEnabled,
        settingsRepo.naturalTypingEnabled,
        settingsRepo.codeEditorMode
    ) { wpm, humanEnabled, natural, codeEditor ->
        CadenceSettings(
            targetWpm = wpm,
            humanTypingEnabled = humanEnabled,
            naturalTypingEnabled = natural,
            codeEditorMode = codeEditor
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CadenceSettings.DEFAULT)

    fun updateDraftText(text: String) {
        _draftText.value = text
    }

    fun sendToPc() {
        val textToSend = _draftText.value
        val settings = cadenceSettings.value
        typingManager.sendToPc(textToSend, settings)
    }

    fun cancelSend() {
        typingManager.cancelSend()
    }

    fun pauseSend() {
        typingManager.pauseSend()
    }

    fun resumeSend() {
        typingManager.resumeSend()
    }

    fun restartSend() {
        typingManager.restartSend()
    }

    fun updateDynamicWpm(wpm: Float) {
        typingManager.updateDynamicWpm(wpm.toInt())
    }
}

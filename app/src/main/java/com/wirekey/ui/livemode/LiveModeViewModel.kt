package com.wirekey.ui.livemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wirekey.WireKeyApp
import com.wirekey.bluetooth.HidConnectionState
import com.wirekey.bluetooth.HidReportBuilder
import com.wirekey.util.HidKeyCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveModeViewModel : ViewModel() {
    private val controller = WireKeyApp.hidKeyboardController

    val connectionState: StateFlow<HidConnectionState> = controller.connectionState

    private val _isCtrlActive = MutableStateFlow(false)
    val isCtrlActive: StateFlow<Boolean> = _isCtrlActive.asStateFlow()

    private val _isAltActive = MutableStateFlow(false)
    val isAltActive: StateFlow<Boolean> = _isAltActive.asStateFlow()

    private val _isWinActive = MutableStateFlow(false)
    val isWinActive: StateFlow<Boolean> = _isWinActive.asStateFlow()

    fun toggleCtrl() { _isCtrlActive.value = !_isCtrlActive.value }
    fun toggleAlt() { _isAltActive.value = !_isAltActive.value }
    fun toggleWin() { _isWinActive.value = !_isWinActive.value }

    fun disconnect() {
        val state = connectionState.value
        if (state is HidConnectionState.Connected) {
            controller.disconnect(state.device)
        }
    }

    fun sendCharacter(char: Char) {
        val reports = HidReportBuilder.charToReports(char) ?: return
        val downReport = reports.first
        val upReport = reports.second

        // Apply sticky modifiers
        var activeModifiers = downReport[0].toInt() and 0xFF
        if (_isCtrlActive.value) activeModifiers = activeModifiers or HidKeyCodes.MODIFIER_LEFT_CTRL
        if (_isAltActive.value) activeModifiers = activeModifiers or HidKeyCodes.MODIFIER_LEFT_ALT
        if (_isWinActive.value) activeModifiers = activeModifiers or HidKeyCodes.MODIFIER_LEFT_GUI
        
        downReport[0] = activeModifiers.toByte()

        sendReports(downReport, upReport)
    }

    fun sendSpecialKey(keyCode: Int) {
        var activeModifiers = 0
        if (_isCtrlActive.value) activeModifiers = activeModifiers or HidKeyCodes.MODIFIER_LEFT_CTRL
        if (_isAltActive.value) activeModifiers = activeModifiers or HidKeyCodes.MODIFIER_LEFT_ALT
        if (_isWinActive.value) activeModifiers = activeModifiers or HidKeyCodes.MODIFIER_LEFT_GUI
        
        val downReport = HidReportBuilder.keyDownReport(keyCode, activeModifiers)
        val upReport = HidReportBuilder.keyUpReport()
        
        sendReports(downReport, upReport)
    }
    
    private fun sendReports(down: ByteArray, up: ByteArray) {
        viewModelScope.launch {
            controller.sendReport(down)
            delay(15) // small delay to ensure host registers press
            controller.sendReport(up)
            
            // Disarm sticky modifiers after use
            _isCtrlActive.value = false
            _isAltActive.value = false
            _isWinActive.value = false
        }
    }
}

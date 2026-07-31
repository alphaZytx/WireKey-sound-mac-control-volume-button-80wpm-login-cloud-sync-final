package com.wirekey.ui.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import com.wirekey.WireKeyApp
import com.wirekey.bluetooth.HidConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.viewModelScope
import com.wirekey.bluetooth.HidReportBuilder

class PairingViewModel : ViewModel() {
    private val controller = WireKeyApp.hidKeyboardController

    val connectionState: StateFlow<HidConnectionState> = controller.connectionState

    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

    private val _connectingDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectingDevice: StateFlow<BluetoothDevice?> = _connectingDevice.asStateFlow()

    init {
        controller.initialize()
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedDevices() {
        _bondedDevices.value = controller.bondedDevices()
    }

    fun connect(device: BluetoothDevice) {
        _connectingDevice.value = device
        controller.connect(device)
    }

    fun sendTestString() {
        val testString = "WireKey test 123"
        viewModelScope.launch {
            for (char in testString) {
                val reports = HidReportBuilder.charToReports(char)
                if (reports != null) {
                    controller.sendReport(reports.first)
                    delay(15)
                    controller.sendReport(reports.second)
                    delay(40)
                }
            }
        }
    }
}

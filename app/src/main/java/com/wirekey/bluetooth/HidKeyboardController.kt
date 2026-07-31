package com.wirekey.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

sealed class HidConnectionState {
    object Unregistered : HidConnectionState()
    object Registered : HidConnectionState()
    object Connecting : HidConnectionState()
    object Reconnecting : HidConnectionState()
    data class Connected(val device: BluetoothDevice) : HidConnectionState()
    object Disconnected : HidConnectionState()
    data class Error(val message: String) : HidConnectionState()
}

@SuppressLint("MissingPermission")
class HidKeyboardController(private val context: Context) {

    private val tag = "HidKeyboardController"

    private val _connectionState = MutableStateFlow<HidConnectionState>(HidConnectionState.Unregistered)
    val connectionState: StateFlow<HidConnectionState> = _connectionState.asStateFlow()

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var retryJob: Job? = null
    private var isManualDisconnect = false
    private var pendingConnectionDevice: BluetoothDevice? = null
    private var isInitialized = false

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(tag, "HID Device Profile connected")
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(tag, "HID Device Profile disconnected")
                hidDevice = null
                _connectionState.value = HidConnectionState.Unregistered
            }
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(tag, "onAppStatusChanged: registered=$registered, device=$pluggedDevice")
            if (registered) {
                _connectionState.value = HidConnectionState.Registered
                pendingConnectionDevice?.let { device ->
                    Log.d(tag, "Executing pending connection for ${device.address}")
                    pendingConnectionDevice = null
                    connect(device)
                }
            } else {
                _connectionState.value = HidConnectionState.Unregistered
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(tag, "onConnectionStateChanged: device=$device, state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    if (_connectionState.value !is HidConnectionState.Reconnecting) {
                        _connectionState.value = HidConnectionState.Connecting
                    }
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    retryJob?.cancel()
                    retryJob = null
                    isManualDisconnect = false
                    connectedDevice = device
                    _connectionState.value = HidConnectionState.Connected(device)
                    
                    // Start foreground service
                    val intent = Intent(context, ConnectionKeepAliveService::class.java)
                    ContextCompat.startForegroundService(context, intent)
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    // Usually transitions to disconnected shortly
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasUnexpectedlyDisconnected = connectedDevice != null && !isManualDisconnect
                    connectedDevice = null

                    if (wasUnexpectedlyDisconnected) {
                        _connectionState.value = HidConnectionState.Reconnecting
                        startReconnectionLoop(device)
                    } else {
                        // If we weren't connected, or user manually disconnected, go straight to Disconnected
                        // But don't overwrite if we are currently mid-reconnection attempt (where it might flap disconnected)
                        if (_connectionState.value !is HidConnectionState.Reconnecting) {
                            _connectionState.value = HidConnectionState.Disconnected
                            // Stop foreground service
                            context.stopService(Intent(context, ConnectionKeepAliveService::class.java))
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            Log.d(tag, "onGetReport: type=$type, id=$id, bufferSize=$bufferSize")
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            Log.d(tag, "onSetReport: type=$type, id=$id")
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            Log.d(tag, "onSetProtocol: protocol=$protocol")
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            Log.d(tag, "onInterruptData: reportId=$reportId")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            Log.d(tag, "onVirtualCableUnplug")
            connectedDevice = null
            _connectionState.value = HidConnectionState.Disconnected
        }
    }

    fun initialize() {
        if (isInitialized) return
        isInitialized = true
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter != null) {
            adapter.getProfileProxy(context.applicationContext, serviceListener, BluetoothProfile.HID_DEVICE)
        } else {
            _connectionState.value = HidConnectionState.Error("Bluetooth not supported")
        }
    }

    private fun registerApp() {
        val hid = hidDevice ?: return
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "WireKey Keyboard",
            "Phone as Bluetooth keyboard",
            "WireKey",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            hidKeyboardDescriptor()
        )
        
        val qosOut = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
            800,
            800,
            0,
            10000,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        val success = hid.registerApp(sdpSettings, null, qosOut, executor, callback)
        if (!success) {
            Log.e(tag, "Failed to initiate app registration")
            _connectionState.value = HidConnectionState.Error("Failed to initiate app registration")
        }
    }

    private fun hidKeyboardDescriptor(): ByteArray {
        val hex = "05 01 09 06 A1 01 05 07 19 E0 29 E7 15 00 25 01 " +
                  "75 01 95 08 81 02 95 01 75 08 81 01 95 05 75 01 " +
                  "05 08 19 01 29 05 91 02 95 01 75 03 91 01 95 06 " +
                  "75 08 15 00 25 65 05 07 19 00 29 65 81 00 C0"
        return hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    }

    fun bondedDevices(): List<BluetoothDevice> {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter ?: return emptyList()
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(tag, "Permission denied for getBondedDevices", e)
            emptyList()
        }
    }

    fun connect(device: BluetoothDevice) {
        if (!isInitialized) {
            initialize()
        }
        if (hidDevice == null || _connectionState.value is HidConnectionState.Unregistered) {
            Log.d(tag, "Queuing connection to ${device.address} until service is ready")
            _connectionState.value = HidConnectionState.Connecting
            pendingConnectionDevice = device
            return
        }

        hidDevice?.let { hid ->
            Log.d(tag, "Attempting to connect to ${device.address}")
            isManualDisconnect = false
            if (_connectionState.value !is HidConnectionState.Reconnecting) {
                _connectionState.value = HidConnectionState.Connecting
            }
            hid.connect(device)
        } ?: run {
            Log.w(tag, "Cannot connect, hidDevice proxy is null")
        }
    }

    fun disconnect(device: BluetoothDevice) {
        hidDevice?.let { hid ->
            Log.d(tag, "Attempting to disconnect from ${device.address}")
            isManualDisconnect = true
            retryJob?.cancel()
            hid.disconnect(device)
        } ?: run {
            Log.w(tag, "Cannot disconnect, hidDevice proxy is null")
        }
    }

    private fun startReconnectionLoop(device: BluetoothDevice) {
        retryJob?.cancel()
        retryJob = controllerScope.launch {
            val backoffs = listOf(1000L, 2000L, 4000L, 8000L, 10000L, 10000L)
            for (delayMs in backoffs) {
                Log.d(tag, "Waiting ${delayMs}ms before reconnection attempt...")
                delay(delayMs.milliseconds)
                if (isManualDisconnect) return@launch
                
                Log.d(tag, "Attempting reconnect...")
                hidDevice?.connect(device)
                
                // Give it some time to connect before next retry
                delay(4000L.milliseconds)
                if (connectedDevice != null) {
                    return@launch // Connected!
                }
            }
            // All retries failed
            Log.e(tag, "Failed to reconnect after retries")
            _connectionState.value = HidConnectionState.Error("Connection lost and could not reconnect.")
            context.stopService(Intent(context, ConnectionKeepAliveService::class.java))
        }
    }

    fun sendReport(reportBytes: ByteArray) {
        val device = connectedDevice
        val hid = hidDevice
        if (device != null && hid != null) {
            try {
                // ID is 0 since our descriptor does not define Report IDs.
                val success = hid.sendReport(device, 0, reportBytes)
                if (!success) {
                    Log.w(tag, "sendReport returned false")
                }
            } catch (e: Exception) {
                Log.e(tag, "Exception during sendReport", e)
                _connectionState.value = HidConnectionState.Error("Failed to send data: ${e.message}")
            }
        } else {
            Log.w(tag, "Cannot send report: device=$device, hid=$hid")
        }
    }

    fun close() {
        retryJob?.cancel()
        context.stopService(Intent(context, ConnectionKeepAliveService::class.java))
        hidDevice?.let { hid ->
            try {
                hid.unregisterApp()
            } catch (e: Exception) {
                Log.e(tag, "Error unregistering app", e)
            }
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        hidDevice = null
    }
}

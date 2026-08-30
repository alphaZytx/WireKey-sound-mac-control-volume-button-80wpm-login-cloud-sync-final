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

/** Renders a raw report for logging and the on-device diagnostics panel. */
private fun ByteArray?.toHexString(): String =
    this?.joinToString(" ") { String.format("%02X", it) } ?: "(null)"

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
                    lastLedState = 0
                    val mode = if (hostProtocolMode == BluetoothHidDevice.PROTOCOL_BOOT_MODE) "boot" else "report"
                    notifyHostActivity("CONNECTED", "link up, $mode protocol")
                    
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
            notifyHostActivity("GET_REPORT", "type=$type id=$id bufferSize=$bufferSize")
            answerGetReport(device, type, id, bufferSize)
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            Log.d(tag, "onSetReport: type=$type, id=$id, data=${data.toHexString()}")
            // Forwarded regardless of [type]: the descriptor declares only Input and
            // Output items (no Feature reports), so any SET_REPORT that reaches us is
            // the LED block. Filtering on REPORT_TYPE_OUTPUT here would risk silently
            // dropping the reports on a host that labels them differently.
            notifyHostOutputReport(data, "onSetReport(type=$type)")
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            Log.d(tag, "onSetProtocol: protocol=$protocol")
            hostProtocolMode = protocol
            val name = if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) "boot" else "report"
            notifyHostActivity("SET_PROTOCOL", "$name mode (raw=$protocol)")
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            Log.d(tag, "onInterruptData: reportId=$reportId, data=${data.toHexString()}")
            notifyHostOutputReport(data, "onInterruptData")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            Log.d(tag, "onVirtualCableUnplug")
            notifyHostActivity("VIRTUAL_CABLE_UNPLUG", "host dropped the HID link")
            connectedDevice = null
            _connectionState.value = HidConnectionState.Disconnected
        }
    }

    /**
     * Receives raw HID *output* reports pushed down by the host -- in practice the 5-bit
     * LED state block (Caps/Num/Scroll Lock) already declared in [hidKeyboardDescriptor].
     *
     * This is the only channel on which a host ever talks back to a HID keyboard, and it
     * is what the Mac Remote Control feature listens on. Wired once by WireKeyApp; null
     * until then, and left null in tests.
     */
    var hostOutputReportListener: ((data: ByteArray?, source: String) -> Unit)? = null

    /**
     * Receives every *other* host->device HID callback -- GET_REPORT, SET_PROTOCOL, cable
     * unplug. None of these carry LED state, but they are how you tell "this Mac never
     * talks to us at all" apart from "this Mac talks, it just doesn't push LED reports",
     * which are two completely different problems with two completely different fixes.
     */
    var hostActivityListener: ((label: String, detail: String) -> Unit)? = null

    /**
     * Invoked on the Bluetooth callback executor, so a misbehaving listener must never be
     * allowed to propagate out and take the HID callback thread down with it.
     */
    private fun notifyHostOutputReport(data: ByteArray?, source: String) {
        // Remembered so a GET_REPORT for the Output report can be answered truthfully.
        // Mirrors LockKeyGestureDetector.extractLedByte: with no Report IDs declared, the
        // ID arrives as its own callback argument, so byte 0 is the LED byte -- unless it
        // is a zero prefix from a stack that includes the ID in the payload anyway.
        if (data != null && data.isNotEmpty()) {
            lastLedState = if (data.size >= 2 && data[0].toInt() == 0) data[1] else data[0]
        }
        try {
            hostOutputReportListener?.invoke(data, source)
        } catch (e: Exception) {
            Log.e(tag, "hostOutputReportListener threw", e)
        }
    }

    private fun notifyHostActivity(label: String, detail: String) {
        try {
            hostActivityListener?.invoke(label, detail)
        } catch (e: Exception) {
            Log.e(tag, "hostActivityListener threw", e)
        }
    }

    // ── Control-channel state, for answering the host's own requests ──────────

    /** Last report we sent upstream; the truthful answer to GET_REPORT(Input). */
    @Volatile
    private var lastInputReport: ByteArray = ByteArray(8)

    /** Last LED byte the host set; the truthful answer to GET_REPORT(Output). */
    @Volatile
    private var lastLedState: Byte = 0

    /** Boot vs report protocol, as last chosen by the host. Diagnostics only. */
    @Volatile
    private var hostProtocolMode: Byte = BluetoothHidDevice.PROTOCOL_REPORT_MODE

    /**
     * Answers a host GET_REPORT, which the app previously logged and then ignored.
     *
     * That silence is very likely why Mac remote control never saw a single LED report.
     * HIDP serializes the control channel: a host may not begin a new control transaction
     * until the previous one is answered, and macOS issues a GET_REPORT shortly after
     * connecting. Leaving it unanswered wedges that channel -- and SET_REPORT, which is how
     * the host pushes the Caps/Num/Scroll-Lock LED block down, is a control transaction.
     * Typing keeps working throughout because it flows the other way on the *interrupt*
     * channel, which is exactly the half-broken picture that was being reported.
     *
     * Runs on the Bluetooth callback executor.
     */
    private fun answerGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
        val hid = hidDevice
        if (device == null || hid == null) {
            Log.w(tag, "Cannot answer GET_REPORT: device=$device, hid=$hid")
            return
        }

        val reply: ByteArray? = when (type) {
            BluetoothHidDevice.REPORT_TYPE_INPUT -> lastInputReport.copyOf()
            BluetoothHidDevice.REPORT_TYPE_OUTPUT -> byteArrayOf(lastLedState)
            // The descriptor declares no Feature reports, so anything else is a request we
            // genuinely cannot serve. An explicit error still closes the transaction, which
            // is the part that matters -- an unanswered one blocks everything behind it.
            else -> null
        }

        try {
            if (reply == null) {
                hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                notifyHostActivity("GET_REPORT reply", "unsupported type=$type -> error handshake")
                return
            }
            // `bufferSize` is the minimum size the host requested, not a maximum.  In
            // particular, never truncate an 8-byte input report to a smaller request:
            // doing so makes the HID control transaction malformed and can leave the
            // host's control channel stuck before it gets as far as an LED SET_REPORT.
            // Padding is safe because the descriptor's unused report bits are zero.
            val payload = if (bufferSize > reply.size) reply.copyOf(bufferSize) else reply
            val sent = hid.replyReport(device, type, id, payload)
            Log.d(tag, "replyReport(type=$type, id=$id) -> $sent, data=${payload.toHexString()}")
            notifyHostActivity(
                "GET_REPORT reply",
                "type=$type sent=$sent data=${payload.toHexString()}"
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to answer GET_REPORT", e)
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
                lastInputReport = reportBytes.copyOf()
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

    /**
     * Presses a key, holds it for [holdMs], then releases it.
     *
     * Exists for the remote-control self-test, which taps the trigger's lock key so the
     * host's LED reply can be observed without the user touching the Mac. Ordinary typing
     * does not go through here -- it is driven by TypingSessionManager's own report pairs.
     *
     * The hold is the whole point. macOS applies a deliberate activation delay to Caps
     * Lock -- it must be held down for tens of milliseconds before the host will accept it,
     * which is what stops a brushed key from flipping your case mid-sentence. A down report
     * followed immediately by an up report is discarded by the host before it changes any
     * lock state, so it produces no LED report and proves nothing about the channel.
     */
    suspend fun sendKeyTap(keyCode: Int, holdMs: Long = DEFAULT_KEY_HOLD_MS) {
        sendReport(HidReportBuilder.keyDownReport(keyCode))
        delay(holdMs.milliseconds)
        sendReport(HidReportBuilder.keyUpReport())
    }

    companion object {
        /**
         * Comfortably clears macOS's Caps Lock activation delay, which defaults to well
         * under 100 ms, without being slow enough to feel like a stuck key.
         */
        const val DEFAULT_KEY_HOLD_MS = 250L
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
        connectedDevice = null
        pendingConnectionDevice = null
        isInitialized = false
        _connectionState.value = HidConnectionState.Unregistered
    }
}

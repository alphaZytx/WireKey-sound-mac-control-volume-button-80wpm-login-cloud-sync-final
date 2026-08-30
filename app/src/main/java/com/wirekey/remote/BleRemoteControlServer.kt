package com.wirekey.remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.wirekey.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The visible state of WireKey's direct Bluetooth LE remote-control endpoint. */
sealed interface BleRemoteControlState {
    data object Disabled : BleRemoteControlState
    data object Starting : BleRemoteControlState
    data object Advertising : BleRemoteControlState
    data class Connected(val deviceName: String) : BleRemoteControlState
    data class Unavailable(val reason: String) : BleRemoteControlState
}

/**
 * A tiny GATT peripheral used by the WireKey Mac Remote helper.
 *
 * Bluetooth HID is intentionally not involved here. The Mac helper captures Caps Lock on
 * the Mac itself, then writes [BleRemoteControlProtocol.VERSION,
 * BleRemoteControlProtocol.TRIGGER] to this characteristic.
 * That keeps the transport independent of whether macOS mirrors its Caps Lock LED to a
 * particular Bluetooth keyboard (it frequently does not).
 *
 * The endpoint is advertised only while the user has enabled Mac Remote Control. It uses a
 * private 128-bit service UUID and accepts a single, fixed two-byte command. The command is
 * deliberately small and has no text or keyboard data on this channel.
 */
@SuppressLint("MissingPermission")
class BleRemoteControlServer(
    private val context: Context,
    settingsRepository: SettingsRepository,
    private val onTrigger: (source: String) -> Unit,
    private val onDiagnostic: (label: String, detail: String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<BleRemoteControlState>(BleRemoteControlState.Disabled)
    val state: StateFlow<BleRemoteControlState> = _state.asStateFlow()

    @Volatile
    private var enabled = false
    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null

    init {
        scope.launch {
            settingsRepository.remoteControlEnabled.collect { isEnabled ->
                enabled = isEnabled
                if (isEnabled) start() else stop()
            }
        }
    }

    private fun start() {
        if (!enabled) return
        if (gattServer != null || _state.value is BleRemoteControlState.Starting ||
            _state.value is BleRemoteControlState.Advertising ||
            _state.value is BleRemoteControlState.Connected
        ) return

        if (!hasRequiredPermissions()) {
            unavailable("Bluetooth permission is not granted")
            return
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            unavailable("Bluetooth is turned off")
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            unavailable("This phone does not support Bluetooth LE advertising")
            return
        }

        val newAdvertiser = adapter.bluetoothLeAdvertiser
        if (newAdvertiser == null) {
            unavailable("Bluetooth LE advertising is unavailable")
            return
        }

        val server = manager.openGattServer(context, gattCallback)
        if (server == null) {
            unavailable("Could not open the Bluetooth LE control service")
            return
        }

        advertiser = newAdvertiser
        gattServer = server
        _state.value = BleRemoteControlState.Starting
        onDiagnostic("BLE", "adding Mac Remote control service")

        if (!server.addService(createService())) {
            unavailable("Could not add the Bluetooth LE control service")
            stop(closeOnly = true)
        }
    }

    private fun createService(): BluetoothGattService {
        val command = BluetoothGattCharacteristic(
            BleRemoteControlProtocol.COMMAND_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        return BluetoothGattService(
            BleRemoteControlProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(command)
        }
    }

    private fun startAdvertising() {
        if (!enabled || gattServer == null) return
        val activeAdvertiser = advertiser ?: run {
            unavailable("Bluetooth LE advertising is unavailable")
            return
        }

        val settings = AdvertiseSettings.Builder()
            // This endpoint is enabled only while Mac Remote Control is enabled. Prefer
            // fast discovery/reconnection over conserving a little battery at this point:
            // the user expects the very next Caps Lock double-tap to work.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleRemoteControlProtocol.SERVICE_UUID))
            .build()

        try {
            activeAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start BLE advertising", e)
            unavailable("Could not advertise the Mac Remote service")
            stop(closeOnly = true)
        }
    }

    private fun stop(closeOnly: Boolean = false) {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop BLE advertising", e)
        }
        advertiser = null

        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Could not close BLE GATT server", e)
        }
        gattServer = null
        connectedDevice = null

        if (!closeOnly) {
            _state.value = BleRemoteControlState.Disabled
            onDiagnostic("BLE", "Mac Remote service stopped")
        }
    }

    private fun unavailable(reason: String) {
        _state.value = BleRemoteControlState.Unavailable(reason)
        onDiagnostic("BLE", reason)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _state.value = BleRemoteControlState.Advertising
            onDiagnostic("BLE", "advertising — launch WireKey Remote on the Mac")
        }

        override fun onStartFailure(errorCode: Int) {
            unavailable("BLE advertising failed (code $errorCode)")
            stop(closeOnly = true)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (service?.uuid != BleRemoteControlProtocol.SERVICE_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startAdvertising()
            } else {
                unavailable("Could not add BLE service (status $status)")
                stop(closeOnly = true)
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                val name = device.safeName()
                _state.value = BleRemoteControlState.Connected(name)
                onDiagnostic("BLE", "connected to $name")
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) connectedDevice = null
                if (enabled) {
                    _state.value = BleRemoteControlState.Advertising
                    onDiagnostic("BLE", "Mac disconnected — waiting to reconnect")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val isValid = enabled &&
                characteristic.uuid == BleRemoteControlProtocol.COMMAND_CHARACTERISTIC_UUID &&
                !preparedWrite &&
                offset == 0 &&
                BleRemoteControlProtocol.isTrigger(value)

            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        if (isValid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        0,
                        null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not acknowledge BLE command", e)
                }
            }

            if (isValid) {
                onDiagnostic("BLE", "remote trigger received from ${device.safeName()}")
                onTrigger("Mac Bluetooth LE")
            } else {
                onDiagnostic("BLE", "ignored malformed remote command from ${device.safeName()}")
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ).all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun BluetoothDevice.safeName(): String = try {
        name?.takeIf { it.isNotBlank() } ?: "Mac"
    } catch (_: SecurityException) {
        "Mac"
    }

    companion object {
        private const val TAG = "BleRemoteControl"

        /** Shared with the macOS helper; change only as a deliberate protocol migration. */
    }
}

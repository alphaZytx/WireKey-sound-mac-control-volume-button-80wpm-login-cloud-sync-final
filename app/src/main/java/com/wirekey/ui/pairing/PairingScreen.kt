package com.wirekey.ui.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wirekey.bluetooth.HidConnectionState
import com.wirekey.bluetooth.PermissionManager

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun PairingScreen(
    onNavigateToLiveMode: () -> Unit,
    onNavigateToComposeMode: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAccount: () -> Unit = {},
    viewModel: PairingViewModel = viewModel()
) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(PermissionManager.hasAllPermissions(context)) }
    var bluetoothEnabled by remember { mutableStateOf(isBluetoothEnabled(context)) }

    val connectionState by viewModel.connectionState.collectAsState()
    val bondedDevices by viewModel.bondedDevices.collectAsState()
    val connectingDevice by viewModel.connectingDevice.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.entries.all { it.value }
    }

    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        bluetoothEnabled = isBluetoothEnabled(context)
        if (bluetoothEnabled) {
            viewModel.refreshBondedDevices()
        }
    }
    
    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(PermissionManager.requiredPermissions())
        }
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            viewModel.refreshBondedDevices()
            if (!bluetoothEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                bluetoothEnableLauncher.launch(enableBtIntent)
            }
        }
    }

    val isConnected = connectionState is HidConnectionState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WireKey") },
                actions = {
                    if (hasPermissions && bluetoothEnabled) {
                        IconButton(onClick = { viewModel.refreshBondedDevices() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
                        }
                    }
                    // Cloud sync's front door. On the start destination because the other
                    // way in — Compose Mode — is only reachable once a host is connected,
                    // which would hide the whole feature from anyone not yet paired.
                    IconButton(onClick = onNavigateToAccount) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Cloud sync account"
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!hasPermissions) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PermissionDeniedCard(
                        onGrantClick = {
                            permissionLauncher.launch(PermissionManager.requiredPermissions())
                        }
                    )
                }
            } else if (!bluetoothEnabled) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BluetoothDisabledCard(
                        onEnableClick = {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            bluetoothEnableLauncher.launch(enableBtIntent)
                        }
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Button(
                        onClick = {
                            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                            }
                            discoverableLauncher.launch(discoverableIntent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text("Make this phone discoverable")
                    }

                    if (bondedDevices.isEmpty()) {
                        EmptyStateView(context)
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(bondedDevices) { device ->
                                DeviceRow(
                                    device = device,
                                    connectionState = connectionState,
                                    connectingDevice = connectingDevice,
                                    onConnectClick = { viewModel.connect(it) },
                                    onTestClick = { viewModel.sendTestString() }
                                )
                                HorizontalDivider()
                            }
                        }

                        // Mode selection buttons — visible once connected
                        if (isConnected) {
                            HorizontalDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Choose a mode:",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = onNavigateToLiveMode,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Live Mode")
                                    }
                                    OutlinedButton(
                                        onClick = onNavigateToComposeMode,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Compose Mode")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceRow(
    device: BluetoothDevice,
    connectionState: HidConnectionState,
    connectingDevice: BluetoothDevice?,
    onConnectClick: (BluetoothDevice) -> Unit,
    onTestClick: () -> Unit
) {
    val isConnected = connectionState is HidConnectionState.Connected && connectionState.device == device
    val isConnecting = connectionState is HidConnectionState.Connecting && connectingDevice == device

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting && !isConnected) { onConnectClick(device) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name ?: "Unknown Device",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isConnected) {
            Column(horizontalAlignment = Alignment.End) {
                SuggestionChip(
                    onClick = { },
                    label = { Text("Connected") },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                OutlinedButton(onClick = onTestClick, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Test Connection")
                }
            }
        } else if (isConnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Connecting…", style = MaterialTheme.typography.bodySmall)
        } else {
            SuggestionChip(
                onClick = { onConnectClick(device) },
                label = { Text("Not connected") }
            )
        }
    }
}

@Composable
fun EmptyStateView(context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No paired devices yet",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Pair one in your phone's Bluetooth settings first.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }) {
            Text("Open Settings")
        }
    }
}

@Composable
fun PermissionDeniedCard(onGrantClick: () -> Unit) {
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Permissions Required",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "WireKey needs Bluetooth access to discover and connect to your computer. Please grant the requested permissions.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGrantClick) {
                Text("Grant permissions")
            }
        }
    }
}

@Composable
fun BluetoothDisabledCard(onEnableClick: () -> Unit) {
    Card(
        modifier = Modifier.padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bluetooth is disabled",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Please turn on Bluetooth to connect to devices.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onEnableClick) {
                Text("Enable Bluetooth")
            }
        }
    }
}

private fun isBluetoothEnabled(context: Context): Boolean {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter
    return bluetoothAdapter?.isEnabled == true
}

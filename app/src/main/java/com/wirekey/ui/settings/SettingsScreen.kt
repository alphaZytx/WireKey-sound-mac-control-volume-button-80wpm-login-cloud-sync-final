package com.wirekey.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wirekey.remote.BleRemoteControlState
import com.wirekey.remote.LedReportLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current

    val targetWpm by viewModel.targetWpm.collectAsState()
    val humanTypingEnabled by viewModel.humanTypingEnabled.collectAsState()
    val naturalEnabled by viewModel.naturalTypingEnabled.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val codeEditorMode by viewModel.codeEditorMode.collectAsState()
    val acousticFeedbackEnabled by viewModel.acousticFeedbackEnabled.collectAsState()
    val soundPackId by viewModel.soundPackId.collectAsState()

    val remoteEnabled by viewModel.remoteControlEnabled.collectAsState()
    val remoteHaptics by viewModel.remoteHapticsEnabled.collectAsState()
    val remoteBleState by viewModel.remoteBleState.collectAsState()
    val remoteReportLog by viewModel.remoteReportLog.collectAsState()
    val remoteReportCount by viewModel.remoteReportCount.collectAsState()
    val remoteHostActivityCount by viewModel.remoteHostActivityCount.collectAsState()
    val remoteLastNotice by viewModel.remoteLastNotice.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Typing Settings Section ──────────────────────────────
            SettingsSectionTitle("Typing Cadence")

            // Human Typing toggle
            ListItem(
                headlineContent = { Text("Human Typing Simulation") },
                supportingContent = {
                    Text(
                        "Simulates realistic human behavior: variable speed, " +
                        "occasional typos with self-correction, fatigue, and " +
                        "natural pauses. Makes typing indistinguishable from a real person."
                    )
                },
                trailingContent = {
                    Switch(
                        checked = humanTypingEnabled,
                        onCheckedChange = { viewModel.setHumanTypingEnabled(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Natural typing pacing (sub-option, only relevant in simple mode)
            ListItem(
                headlineContent = { Text("Natural typing pacing") },
                supportingContent = {
                    Text(
                        if (humanTypingEnabled)
                            "Handled automatically by the simulation engine"
                        else
                            "Adds realistic micro-pauses between words and sentences"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = if (humanTypingEnabled) true else naturalEnabled,
                        onCheckedChange = { viewModel.setNaturalTypingEnabled(it) },
                        enabled = !humanTypingEnabled
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Code Editor Mode toggle
            ListItem(
                headlineContent = { Text("Code Editor Mode") },
                supportingContent = {
                    Text(
                        if (codeEditorMode)
                            "ON — Handles auto-indent and auto-close brackets (VS Code, Sublime, etc.)"
                        else
                            "OFF — Plain text mode for Notepad and simple editors"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = codeEditorMode,
                        onCheckedChange = { viewModel.setCodeEditorMode(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // WPM Slider
            Column(modifier = Modifier.padding(16.dp)) {
                val speedLabel = when {
                    targetWpm <= 30 -> "Very Slow"
                    targetWpm <= 45 -> "Slow"
                    targetWpm <= 70 -> "Normal"
                    targetWpm <= 90 -> "Fast"
                    targetWpm <= 120 -> "Very Fast"
                    else -> "Ultra Fast"
                }

                Text(
                    "Typing Speed: $targetWpm WPM ($speedLabel)",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = targetWpm.toFloat(),
                    onValueChange = { viewModel.setTargetWpm(it.roundToInt()) },
                    valueRange = 20f..150f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("20 WPM", style = MaterialTheme.typography.bodySmall)
                    Text("150 WPM", style = MaterialTheme.typography.bodySmall)
                }

                if (humanTypingEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "🧠 The simulation adds natural variation around your target WPM. " +
                                   "It will occasionally make typos and correct them, just like a real person.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // ── Appearance Section ───────────────────────────────────
            SettingsSectionTitle("Appearance")

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = appTheme == "system",
                        onClick = { viewModel.setAppTheme("system") },
                        label = { Text("System") }
                    )
                    FilterChip(
                        selected = appTheme == "light",
                        onClick = { viewModel.setAppTheme("light") },
                        label = { Text("Light") }
                    )
                    FilterChip(
                        selected = appTheme == "dark",
                        onClick = { viewModel.setAppTheme("dark") },
                        label = { Text("Dark") }
                    )
                }
            }

            // ── Acoustic Typing Feedback Section ─────────────────────
            SettingsSectionTitle("Acoustic Typing Feedback")

            ListItem(
                headlineContent = { Text("Keyboard Click Sounds") },
                supportingContent = {
                    Text("Plays real recorded MacBook keystroke sounds in sync with every key sent, in both Live and Compose Mode")
                },
                trailingContent = {
                    Switch(
                        checked = acousticFeedbackEnabled,
                        onCheckedChange = { viewModel.setAcousticFeedbackEnabled(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Keyboard Sound Profile", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))

                var soundPackMenuExpanded by remember { mutableStateOf(false) }
                val selectedPackName = viewModel.availableSoundPacks
                    .firstOrNull { it.id == soundPackId }?.displayName ?: soundPackId

                ExposedDropdownMenuBox(
                    expanded = soundPackMenuExpanded,
                    onExpandedChange = {
                        if (acousticFeedbackEnabled) soundPackMenuExpanded = it
                    }
                ) {
                    TextField(
                        value = selectedPackName,
                        onValueChange = {},
                        readOnly = true,
                        enabled = acousticFeedbackEnabled,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = soundPackMenuExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = soundPackMenuExpanded,
                        onDismissRequest = { soundPackMenuExpanded = false }
                    ) {
                        viewModel.availableSoundPacks.forEach { pack ->
                            DropdownMenuItem(
                                text = { Text(pack.displayName) },
                                onClick = {
                                    viewModel.setSoundPackId(pack.id)
                                    soundPackMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ── Mac Remote Control Section ───────────────────────────
            SettingsSectionTitle("Mac Remote Control")

            ListItem(
                headlineContent = { Text("Control sending from your Mac") },
                supportingContent = {
                    Text(
                        "Use the WireKey Remote helper on your Mac. It detects two Caps Lock " +
                        "presses and sends a direct Bluetooth LE command to this phone."
                    )
                },
                trailingContent = {
                    Switch(
                        checked = remoteEnabled,
                        onCheckedChange = { viewModel.setRemoteControlEnabled(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Bluetooth LE status", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bleRemoteStateLabel(remoteBleState),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (remoteBleState is BleRemoteControlState.Unavailable)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Setup",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "1. Turn this switch on.\n" +
                        "2. Build and open WireKey Remote.app on your Mac.\n" +
                        "3. Allow its Bluetooth and Input Monitoring permissions.\n" +
                        "4. In Compose Mode, press Caps Lock twice to Start, Pause, or Resume.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            ListItem(
                headlineContent = { Text("Vibrate on remote command") },
                supportingContent = {
                    Text("Different buzz patterns for Start, Pause, Resume and rejected — so you can tell what happened without looking at the phone")
                },
                trailingContent = {
                    Switch(
                        checked = remoteHaptics,
                        onCheckedChange = { viewModel.setRemoteHapticsEnabled(it) },
                        enabled = remoteEnabled
                    )
                }
            )

            RemoteDiagnosticsCard(
                reportCount = remoteReportCount,
                hostActivityCount = remoteHostActivityCount,
                reportLog = remoteReportLog,
                lastNoticeText = remoteLastNotice?.message,
                featureEnabled = remoteEnabled,
                onClear = { viewModel.clearRemoteDiagnostics() }
            )

            // ── Device Section ───────────────────────────────────────
            SettingsSectionTitle("Device")

            ListItem(
                headlineContent = { Text("Manage paired devices") },
                supportingContent = { Text("Open Android Bluetooth settings to unpair devices") },
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            )
        }
    }
}

/** Shows Bluetooth LE remote activity, alongside legacy HID traffic when one exists. */
@Composable
private fun RemoteDiagnosticsCard(
    reportCount: Int,
    hostActivityCount: Int,
    reportLog: List<LedReportLog>,
    lastNoticeText: String?,
    featureEnabled: Boolean,
    onClear: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val clipboard = LocalClipboardManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(onClick = {
                        clipboard.setText(
                            AnnotatedString(
                                buildDiagnosticsReport(
                                    reportCount, hostActivityCount, featureEnabled, reportLog, timeFormat
                                )
                            )
                        )
                    }) { Text("Copy") }
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }

            Text(
                "Legacy HID LED reports: $reportCount",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Remote/Bluetooth events: $hostActivityCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!featureEnabled) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Mac Remote Control is OFF. Turn it on to advertise the Bluetooth LE " +
                        "service for the Mac helper.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (lastNoticeText != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Last action: $lastNoticeText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (reportLog.isEmpty()) {
                Text(
                    "No activity yet. Open WireKey Remote on the Mac; its connection and " +
                        "every accepted Caps Lock trigger appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                reportLog.asReversed().forEach { entry ->
                    val timestamp = timeFormat.format(Date(entry.wallClockMs))
                    if (entry.isHostActivity) {
                        Text(
                            text = "$timestamp  ${entry.source}  —  ${entry.rawHex}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val state = when (entry.triggerBitOn) {
                            true -> "lock key ON"
                            false -> "lock key off"
                            null -> "unparsed"
                        }
                        val edgeMark = if (entry.wasEdge) "  ← change" else ""
                        val gap = entry.gapSincePreviousEdgeMs?.let { "  gap ${it}ms" } ?: ""
                        Text(
                            text = "$timestamp  [${entry.rawHex}]  $state$edgeMark$gap",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.wasEdge) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "        via ${entry.source}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * Renders the diagnostics panel as plain text for the clipboard.
 *
 * Whether the host sends LED reports at all can only be answered on the user's own Mac,
 * so the evidence has to be able to travel back off the phone without adb.
 */
private fun buildDiagnosticsReport(
    reportCount: Int,
    hostActivityCount: Int,
    featureEnabled: Boolean,
    reportLog: List<LedReportLog>,
    timeFormat: SimpleDateFormat
): String = buildString {
    appendLine("WireKey remote-control diagnostics")
    appendLine("feature enabled: $featureEnabled")
    appendLine("legacy HID LED reports: $reportCount")
    appendLine("remote/Bluetooth events: $hostActivityCount")
    appendLine("--")
    if (reportLog.isEmpty()) {
        appendLine("(log empty)")
    } else {
        reportLog.forEach { entry ->
            val stamp = timeFormat.format(Date(entry.wallClockMs))
            if (entry.isHostActivity) {
                appendLine("$stamp  ${entry.source}  ${entry.rawHex}")
            } else {
                val state = when (entry.triggerBitOn) {
                    true -> "ON"
                    false -> "off"
                    null -> "unparsed"
                }
                val edge = if (entry.wasEdge) " CHANGE" else ""
                val gap = entry.gapSincePreviousEdgeMs?.let { " gap=${it}ms" } ?: ""
                appendLine("$stamp  [${entry.rawHex}]  $state$edge$gap  via ${entry.source}")
            }
        }
    }
}

private fun bleRemoteStateLabel(state: BleRemoteControlState): String = when (state) {
    BleRemoteControlState.Disabled -> "Off — turn on Mac Remote Control to make this phone discoverable."
    BleRemoteControlState.Starting -> "Starting Bluetooth LE service…"
    BleRemoteControlState.Advertising -> "Ready — waiting for WireKey Remote on the Mac."
    is BleRemoteControlState.Connected -> "Connected to ${state.deviceName}."
    is BleRemoteControlState.Unavailable -> "Unavailable: ${state.reason}"
}

package com.wirekey.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

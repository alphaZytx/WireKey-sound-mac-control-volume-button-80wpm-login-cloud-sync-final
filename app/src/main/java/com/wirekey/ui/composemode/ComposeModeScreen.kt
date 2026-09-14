package com.wirekey.ui.composemode

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wirekey.WireKeyApp
import com.wirekey.cloud.SyncStatus
import com.wirekey.data.SentMessage
import com.wirekey.ui.cloud.syncStatusColor
import com.wirekey.ui.cloud.syncStatusLabel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeModeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToAccount: () -> Unit = {},
    viewModel: ComposeModeViewModel = viewModel()
) {
    val draftText by viewModel.draftText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val sendProgress by viewModel.sendProgress.collectAsState()
    val remainingTimeMs by viewModel.remainingTimeMs.collectAsState()
    val dynamicWpm by viewModel.dynamicWpm.collectAsState()

    val cadenceSettings by viewModel.cadenceSettings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val sendHistory by viewModel.sendHistory.collectAsState()

    val activeCharIndex by viewModel.activeCharIndex.collectAsState()
    val sentTrailRanges by viewModel.displayedTrailRanges.collectAsState()

    val syncStatus by viewModel.syncStatus.collectAsState()
    val isSignedIn by viewModel.isSignedIn.collectAsState()

    val markerA by viewModel.markerA.collectAsState()
    val markerB by viewModel.markerB.collectAsState()
    val partialExecutionEnabled by viewModel.partialExecutionEnabled.collectAsState()
    val placementMode by viewModel.placementMode.collectAsState()

    // Claim the volume keys for typing speed for exactly as long as this screen is composed.
    // Leaving Compose Mode disposes this and hands the keys straight back to the system, so
    // no other screen -- and nothing outside the app -- is affected.
    DisposableEffect(viewModel) {
        val registration = WireKeyApp.volumeKeyWpmController.register { deltaWpm ->
            viewModel.adjustDynamicWpm(deltaWpm)
        }
        onDispose { registration.unregister() }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var showHistoryDialog by remember { mutableStateOf(false) }

    // Draft editing (Clear All / Paste / restoring from history) is allowed whenever we're
    // not actively streaming keystrokes — idle, or paused mid-send. It's blocked while
    // actively sending since the text field is read-only then and currentText is already
    // snapshotted by the typing session, independent of further draftText edits.
    val canEditDraft = !isSending || isPaused

    LaunchedEffect(connectionState) {
        when (connectionState) {
            is com.wirekey.bluetooth.HidConnectionState.Disconnected,
            is com.wirekey.bluetooth.HidConnectionState.Error -> {
                onNavigateBack()
            }
            is com.wirekey.bluetooth.HidConnectionState.Reconnecting -> {
                snackbarHostState.showSnackbar(
                    message = "Connection lost — reconnecting…",
                    duration = SnackbarDuration.Indefinite
                )
            }
            is com.wirekey.bluetooth.HidConnectionState.Connected -> {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            else -> {}
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sendCompleteEvent.collectLatest {
            snackbarHostState.showSnackbar("Message sent to PC successfully!")
        }
    }

    // The editor changing under the user's hands is startling without an explanation. This
    // reports what happened after the fact — it is not a confirmation prompt, and the text
    // has already been replaced by the time it appears.
    LaunchedEffect(Unit) {
        viewModel.remoteUpdateEvent.collectLatest {
            snackbarHostState.showSnackbar("Compose text updated from cloud")
        }
    }

    // Failures are surfaced once, with the reason; the status line keeps saying "Sync failed"
    // for as long as it is true.
    LaunchedEffect(syncStatus) {
        val status = syncStatus
        if (status is SyncStatus.Failed) {
            snackbarHostState.showSnackbar("Sync failed — ${status.message}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose Mode") },
                actions = {
                    TextButton(onClick = { showHistoryDialog = true }) {
                        Text("History")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // ── Cloud sync bar ───────────────────────────────────────────
            // Above the existing controls and outside every one of them, so nothing about
            // markers, speed or sending changes. Absent entirely on builds with no Supabase
            // project, which keeps Compose Mode pixel-identical for anyone not using sync.
            if (viewModel.cloudSyncAvailable) {
                CloudSyncBar(
                    status = syncStatus,
                    isSignedIn = isSignedIn,
                    onSync = { viewModel.syncNow() },
                    onOpenAccount = onNavigateToAccount
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Marker placement toolbar ─────────────────────────────────
            if (!isSending) {
                MarkerToolbar(
                    placementMode = placementMode,
                    markerA = markerA,
                    markerB = markerB,
                    onSelectStartingPoint = { viewModel.activateStartPlacement() },
                    onSelectLastPoint = { viewModel.activateEndPlacement() },
                    onClearMarkers = { viewModel.clearMarkers() }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            CodeEditorTextField(
                value = draftText,
                onValueChange = { viewModel.updateDraftText(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                readOnly = isSending,
                markerAOffset = markerA,
                markerBOffset = markerB,
                activeCharIndex = activeCharIndex,
                sentTrailRanges = sentTrailRanges,
                placementMode = placementMode,
                onTapForPlacement = { offset -> viewModel.onTextFieldTapForPlacement(offset) }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── Marker status row ────────────────────────────────────────────
            if (markerA >= 0 || markerB >= 0) {
                MarkerStatusRow(
                    text = draftText,
                    markerA = markerA,
                    markerB = markerB,
                    onClearMarkers = { viewModel.clearMarkers() }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row {
                    TextButton(
                        onClick = { viewModel.clearDraftText() },
                        enabled = draftText.isNotEmpty() && canEditDraft
                    ) {
                        Text("Clear All")
                    }
                    TextButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (clipText.isNullOrEmpty()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Clipboard is empty")
                                }
                            } else {
                                viewModel.updateDraftText(draftText + clipText)
                            }
                        },
                        enabled = canEditDraft
                    ) {
                        Text("Paste")
                    }
                }
                Text(
                    text = "${draftText.length} characters",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cadence info card
            val modeLabel = if (cadenceSettings.humanTypingEnabled) "Human Simulation" else "Simple"
            val speedLabel = when {
                cadenceSettings.targetWpm <= 30 -> "Very Slow"
                cadenceSettings.targetWpm <= 45 -> "Slow"
                cadenceSettings.targetWpm <= 70 -> "Normal"
                cadenceSettings.targetWpm <= 90 -> "Fast"
                cadenceSettings.targetWpm <= 120 -> "Very Fast"
                else -> "Ultra Fast"
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$modeLabel · ${cadenceSettings.targetWpm} WPM ($speedLabel)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onNavigateToSettings, enabled = !isSending) {
                        Text("Change")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isSending) {
                val remainingSeconds = (remainingTimeMs / 1000).toInt()
                val minutes = remainingSeconds / 60
                val seconds = remainingSeconds % 60
                val formattedTime = String.format("%02d:%02d", minutes, seconds)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sending...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Time left: $formattedTime",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { sendProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Typing Speed: $dynamicWpm WPM",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    androidx.compose.material3.Slider(
                        value = dynamicWpm.toFloat(),
                        onValueChange = { viewModel.updateDynamicWpm(it) },
                        valueRange = 10f..250f,
                        steps = 240,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isPaused) {
                            Button(
                                onClick = { viewModel.resumeSend() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Resume")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.pauseSend() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Text("Pause")
                            }
                        }

                        Button(
                            onClick = { viewModel.restartSend() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Text("Restart")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.cancelSend() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Cancel Sending")
                    }
                }
            } else {
                Button(
                    onClick = { viewModel.sendToPc() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = draftText.isNotEmpty()
                ) {
                    val label = when {
                        markerA >= 0 && markerB >= 0 -> "Start Sending Keystrokes (A→B)"
                        markerA >= 0               -> "Start Sending Keystrokes (A→End)"
                        markerB >= 0               -> "Start Sending Keystrokes (Start→B)"
                        else                        -> "Start Sending Keystrokes"
                    }
                    Text(label)
                }
            }
        }
    }

    if (showHistoryDialog) {
        SendHistoryDialog(
            history = sendHistory,
            canResend = !isSending,
            canRestore = canEditDraft,
            onRestore = { text ->
                viewModel.restoreFromHistory(text)
                showHistoryDialog = false
            },
            onResend = { text ->
                viewModel.resendFromHistory(text)
                showHistoryDialog = false
            },
            onDelete = { index -> viewModel.deleteHistoryEntry(index) },
            onClearAll = { viewModel.clearHistory() },
            onDismiss = { showHistoryDialog = false }
        )
    }
}

// ── Cloud Sync Bar ───────────────────────────────────────────────────────────

/**
 * One slim row: what sync is doing on the left, the button that pushes this editor to the
 * cloud on the right.
 *
 * Sync stays enabled during a send. Pushing text to Supabase has no effect on keystrokes
 * already in flight, so there is no reason to take the control away.
 */
@Composable
private fun CloudSyncBar(
    status: SyncStatus,
    isSignedIn: Boolean,
    onSync: () -> Unit,
    onOpenAccount: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (status is SyncStatus.Syncing || status is SyncStatus.Connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Text(
                text = syncStatusLabel(status),
                style = MaterialTheme.typography.labelMedium,
                color = syncStatusColor(status),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = onOpenAccount,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isSignedIn) "Account" else "Sign in",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            if (isSignedIn) {
                TextButton(
                    onClick = onSync,
                    enabled = status !is SyncStatus.Syncing,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = "Sync", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ── Marker Toolbar ────────────────────────────────────────────────────────────

/**
 * Two dedicated icon-buttons to activate START / END marker placement mode.
 * While a mode is active, the button is highlighted and the user's next tap
 * inside the text field drops the marker at that position.
 */
@Composable
private fun MarkerToolbar(
    placementMode: MarkerPlacementMode,
    markerA: Int,
    markerB: Int,
    onSelectStartingPoint: () -> Unit,
    onSelectLastPoint: () -> Unit,
    onClearMarkers: () -> Unit
) {
    val startActive = placementMode == MarkerPlacementMode.START
    val endActive   = placementMode == MarkerPlacementMode.END
    val hasMarkers  = markerA >= 0 || markerB >= 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Select Starting Point button ─────────────────────────────────
        OutlinedButton(
            onClick = onSelectStartingPoint,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (startActive)
                    Color(0xFF43A047).copy(alpha = 0.15f)
                else
                    Color.Transparent,
                contentColor = if (startActive || markerA >= 0)
                    Color(0xFF2E7D32)
                else
                    MaterialTheme.colorScheme.onSurface
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = if (startActive) 2.dp else 1.dp,
                color = if (startActive || markerA >= 0)
                    Color(0xFF43A047)
                else
                    MaterialTheme.colorScheme.outline
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (startActive) "▶ Tap to place Start" else if (markerA >= 0) "▶ Start set" else "▶ Select Starting Point",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (startActive) FontWeight.Bold else FontWeight.Normal
            )
        }

        // ── Select Last Point button ─────────────────────────────────────
        OutlinedButton(
            onClick = onSelectLastPoint,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (endActive)
                    Color(0xFFE53935).copy(alpha = 0.15f)
                else
                    Color.Transparent,
                contentColor = if (endActive || markerB >= 0)
                    Color(0xFFC62828)
                else
                    MaterialTheme.colorScheme.onSurface
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = if (endActive) 2.dp else 1.dp,
                color = if (endActive || markerB >= 0)
                    Color(0xFFE53935)
                else
                    MaterialTheme.colorScheme.outline
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (endActive) "■ Tap to place End" else if (markerB >= 0) "■ End set" else "■ Select Last Point",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (endActive) FontWeight.Bold else FontWeight.Normal
            )
        }

        // ── Clear button — only visible when at least one marker is placed ─
        if (hasMarkers) {
            TextButton(
                onClick = onClearMarkers,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Text(
                    text = "✕",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Marker Status Row ────────────────────────────────────────────────────────

/**
 * Converts a flat character offset into a human-readable "line X, col Y" string.
 */
private fun offsetToLineCol(text: String, offset: Int): String {
    if (offset < 0 || offset > text.length) return "—"
    val sub = text.substring(0, offset)
    val line = sub.count { it == '\n' } + 1
    val col = offset - (sub.lastIndexOf('\n') + 1) + 1
    return "L$line C$col"
}

@Composable
private fun MarkerStatusRow(
    text: String,
    markerA: Int,
    markerB: Int,
    onClearMarkers: () -> Unit
) {
    val selectionLength = when {
        markerA >= 0 && markerB > markerA -> markerB - markerA
        markerA >= 0 && markerB < 0       -> text.length - markerA
        markerA < 0 && markerB >= 0       -> markerB
        else                               -> 0
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Marker A label
            if (markerA >= 0) {
                Text(
                    text = "▶ Start: ${offsetToLineCol(text, markerA)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF2E7D32), // dark green
                    fontWeight = FontWeight.Bold
                )
            }
            if (markerA >= 0 && markerB >= 0) {
                Text(
                    text = "  →  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            // Marker B label
            if (markerB >= 0) {
                Text(
                    text = "■ End: ${offsetToLineCol(text, markerB)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC62828), // dark red
                    fontWeight = FontWeight.Bold
                )
            }
            // Selection length summary
            if (selectionLength > 0) {
                Text(
                    text = "  ($selectionLength chars)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// ── Send History Dialog ──────────────────────────────────────────────────────

@Composable
fun SendHistoryDialog(
    history: List<SentMessage>,
    canResend: Boolean,
    canRestore: Boolean,
    onRestore: (String) -> Unit,
    onResend: (String) -> Unit,
    onDelete: (Int) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Send History")
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("Clear")
                    }
                }
            }
        },
        text = {
            if (history.isEmpty()) {
                Text(
                    text = "Nothing sent yet. Messages you send from Compose Mode will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(history) { index, entry ->
                        HistoryRow(
                            entry = entry,
                            canResend = canResend,
                            canRestore = canRestore,
                            onRestore = { onRestore(entry.text) },
                            onResend = { onResend(entry.text) },
                            onDelete = { onDelete(index) }
                        )
                        if (index != history.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun HistoryRow(
    entry: SentMessage,
    canResend: Boolean,
    canRestore: Boolean,
    onRestore: () -> Unit,
    onResend: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canRestore, onClick = onRestore)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = DateUtils.getRelativeTimeSpanString(
                    entry.timestampMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                TextButton(onClick = onResend, enabled = canResend) {
                    Text("Resend")
                }
                TextButton(onClick = onDelete) {
                    Text("Delete")
                }
            }
        }
    }
}

// ── Code Editor Text Field ───────────────────────────────────────────────────

/**
 * A code-editor-style text field with:
 * - Line number gutter
 * - Horizontal + vertical scrolling
 * - Ruled lines
 * - Optional Partial Execution markers (A = start, B = end) drawn as
 *   coloured vertical bars.
 *
 * When [placementMode] is non-NONE, the text field intercepts the next single
 * tap and calls [onTapForPlacement] with the character offset at that position
 * instead of moving the cursor normally. The caller is responsible for
 * routing that offset to the correct marker via the ViewModel.
 *
 * @param markerAOffset   Character offset for Marker A (-1 = not placed).
 * @param markerBOffset   Character offset for Marker B (-1 = not placed).
 * @param placementMode   The currently active placement mode.
 * @param onTapForPlacement Called with the tapped character offset when placement mode is active.
 */
@Composable
fun CodeEditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    markerAOffset: Int = -1,
    markerBOffset: Int = -1,
    activeCharIndex: Int = -1,
    sentTrailRanges: List<IntRange> = emptyList(),
    placementMode: MarkerPlacementMode = MarkerPlacementMode.NONE,
    onTapForPlacement: ((Int) -> Unit)? = null
) {
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 24.sp
    )

    val density = LocalDensity.current
    val lineHeightPx = with(density) { textStyle.lineHeight.toPx() }
    val paddingTopPx = with(density) { 16.dp.toPx() }

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    val lineCount = value.count { it == '\n' } + 1

    val surfaceColor = MaterialTheme.colorScheme.surface
    val gutterColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val outlineColor = MaterialTheme.colorScheme.outline
    val ruledLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)

    // Marker colours
    val markerAColor = Color(0xFF43A047)   // green
    val markerBColor = Color(0xFFE53935)   // red
    val selectionTintColor = Color(0x2243A047) // subtle green tint for A→B region
    val activeCharColor = Color(0x66FFEB3B) // yellow highlight for active char
    val sentTrailColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) // sent-text shading

    // textLayoutResult is updated by BasicTextField's onTextLayout callback and used
    // to convert character offsets → pixel positions for drawing the marker bars.
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    
    var boxHeight by remember { mutableIntStateOf(0) }
    var boxWidth by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(activeCharIndex, boxHeight, boxWidth, textLayoutResult) {
        if (activeCharIndex >= 0 && boxHeight > 0) {
            textLayoutResult?.let { layoutResult ->
                val safeIndex = activeCharIndex.coerceIn(0, maxOf(0, value.length - 1))
                if (safeIndex < value.length) {
                    val rect = layoutResult.getBoundingBox(safeIndex)
                    
                    val yTop = rect.top + paddingTopPx
                    val yBottom = rect.bottom + paddingTopPx
                    
                    val currentScrollY = verticalScrollState.value
                    val visibleTop = currentScrollY.toFloat()
                    val visibleBottom = currentScrollY + boxHeight.toFloat()
                    
                    if (yBottom > visibleBottom) {
                        verticalScrollState.animateScrollTo((yBottom - boxHeight + lineHeightPx).toInt().coerceAtLeast(0))
                    } else if (yTop < visibleTop) {
                        verticalScrollState.animateScrollTo((yTop - lineHeightPx).toInt().coerceAtLeast(0))
                    }
                    
                    val gutterWidthPx = with(density) { 40.dp.toPx() }
                    val xLeft = rect.left + gutterWidthPx
                    val xRight = rect.right + gutterWidthPx
                    
                    val currentScrollX = horizontalScrollState.value
                    val visibleLeft = currentScrollX.toFloat() + gutterWidthPx
                    val visibleRight = currentScrollX + boxWidth.toFloat()
                    
                    if (xRight > visibleRight) {
                        horizontalScrollState.animateScrollTo((xRight - boxWidth + 50f).toInt().coerceAtLeast(0))
                    } else if (xLeft < visibleLeft) {
                        horizontalScrollState.animateScrollTo((xLeft - gutterWidthPx - 50f).toInt().coerceAtLeast(0))
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .background(surfaceColor)
            .border(1.dp, outlineColor, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .drawBehind {
                val gutterWidth = 40.dp.toPx()

                // 1. Draw Gutter Background
                drawRect(
                    color = gutterColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(gutterWidth, size.height)
                )

                // 2. Draw Divider
                drawLine(
                    color = outlineColor,
                    start = Offset(gutterWidth, 0f),
                    end = Offset(gutterWidth, size.height),
                    strokeWidth = 1.dp.toPx()
                )

                // 3. Draw Ruled Lines
                val scrollY = verticalScrollState.value
                val offset = (paddingTopPx - scrollY) % lineHeightPx
                var y = offset
                if (y < 0) y += lineHeightPx

                // Add the text baseline offset to draw the line *under* the text
                y += lineHeightPx

                while (y < size.height) {
                    drawLine(
                        color = ruledLineColor,
                        start = Offset(gutterWidth, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += lineHeightPx
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    boxHeight = coordinates.size.height
                    boxWidth = coordinates.size.width
                }
                .verticalScroll(verticalScrollState)
        ) {
            // Gutter Numbers
            Column(
                modifier = Modifier
                    .width(40.dp)
                    .padding(vertical = 16.dp)
            ) {
                for (i in 1..lineCount) {
                    Text(
                        text = i.toString(),
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 6.dp),
                        textAlign = TextAlign.End,
                        maxLines = 1
                    )
                }
            }

            // Text Content — wrapped in a Box so we can draw marker overlays on top
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScrollState)
            ) {
                if (placementMode != MarkerPlacementMode.NONE && onTapForPlacement != null) {
                    // ── PLACEMENT MODE: show text as non-interactive + capture taps ──
                    // BasicTextField swallows all touch events internally, so we
                    // cannot intercept taps with pointerInput when it's rendered.
                    // Instead, render the text as a plain Text composable and overlay
                    // a transparent tap target on the entire area.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .pointerInput(placementMode) {
                                detectTapGestures(
                                    onTap = { tapPosition ->
                                        val result = textLayoutResult
                                        if (result != null) {
                                            val charOffset =
                                                result.getOffsetForPosition(tapPosition)
                                            onTapForPlacement(charOffset)
                                        }
                                    }
                                )
                            }
                            .drawBehind {
                                val tlr = textLayoutResult ?: return@drawBehind
                                val textLen = value.length

                                // Draw the "sent text" trail shading. Each committed range is
                                // frozen at the exact [start, end) the keystroke loop actually
                                // covered — drawn as its own path, never derived from the live
                                // marker offsets, so it can't drift when markers move later.
                                for (range in sentTrailRanges) {
                                    val rangeStart = range.first.coerceIn(0, textLen)
                                    val rangeEnd = range.last.coerceIn(0, textLen)
                                    if (rangeEnd > rangeStart) {
                                        val trailPath = tlr.getPathForRange(rangeStart, rangeEnd)
                                        drawPath(path = trailPath, color = sentTrailColor)
                                    }
                                }

                                // Draw selection tint between A and B
                                if (markerAOffset in 0..textLen &&
                                    markerBOffset in 0..textLen &&
                                    markerBOffset > markerAOffset
                                ) {
                                    val path = tlr.getPathForRange(markerAOffset, markerBOffset)
                                    drawPath(path = path, color = selectionTintColor)
                                }

                                // Draw Marker A (green vertical bar)
                                if (markerAOffset in 0..textLen) {
                                    val clampedA = markerAOffset.coerceAtMost(textLen)
                                    val rect: Rect = tlr.getCursorRect(clampedA)
                                    drawLine(
                                        color = markerAColor,
                                        start = Offset(rect.left, rect.top),
                                        end = Offset(rect.left, rect.bottom),
                                        strokeWidth = 3.dp.toPx()
                                    )
                                    val triSize = 6.dp.toPx()
                                    drawRect(
                                        color = markerAColor,
                                        topLeft = Offset(rect.left, rect.top),
                                        size = Size(triSize, triSize * 0.6f)
                                    )
                                }

                                // Draw Marker B (red vertical bar)
                                if (markerBOffset in 0..textLen) {
                                    val clampedB = markerBOffset.coerceAtMost(textLen)
                                    val rect: Rect = tlr.getCursorRect(clampedB)
                                    drawLine(
                                        color = markerBColor,
                                        start = Offset(rect.left, rect.top),
                                        end = Offset(rect.left, rect.bottom),
                                        strokeWidth = 3.dp.toPx()
                                    )
                                    val sqSize = 6.dp.toPx()
                                    drawRect(
                                        color = markerBColor,
                                        topLeft = Offset(rect.left, rect.top),
                                        size = Size(sqSize, sqSize * 0.6f)
                                    )
                                }
                                
                                // Draw Active Character Highlight
                                if (activeCharIndex in 0 until textLen) {
                                    val rect = tlr.getBoundingBox(activeCharIndex)
                                    drawRect(
                                        color = activeCharColor,
                                        topLeft = Offset(rect.left, rect.top),
                                        size = Size(rect.width, rect.height)
                                    )
                                }
                            }
                    ) {
                        // Render text using BasicText (foundation) instead of
                        // Material3 Text — BasicText always exposes onTextLayout,
                        // whereas Material3 Text only gained it in Compose 1.7+.
                        if (value.isEmpty()) {
                            BasicText(
                                text = "Tap where you want to place the marker…",
                                style = textStyle.copy(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                ),
                                onTextLayout = { result -> textLayoutResult = result }
                            )
                        } else {
                            BasicText(
                                text = value,
                                style = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                                onTextLayout = { result -> textLayoutResult = result }
                            )
                        }
                    }
                } else {
                    // ── NORMAL MODE: editable BasicTextField ──────────────────
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        readOnly = readOnly,
                        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        onTextLayout = { result ->
                            textLayoutResult = result
                        },
                        decorationBox = { innerTextField ->
                            // ── Draw selection highlight + marker bars ───────────
                            Box(
                                modifier = Modifier.drawBehind {
                                    val tlr = textLayoutResult ?: return@drawBehind
                                    val textLen = value.length

                                    // Draw the "sent text" trail shading. Each committed range is
                                    // frozen at the exact [start, end) the keystroke loop actually
                                    // covered — drawn as its own path, never derived from the live
                                    // marker offsets, so it can't drift when markers move later.
                                    for (range in sentTrailRanges) {
                                        val rangeStart = range.first.coerceIn(0, textLen)
                                        val rangeEnd = range.last.coerceIn(0, textLen)
                                        if (rangeEnd > rangeStart) {
                                            val trailPath = tlr.getPathForRange(rangeStart, rangeEnd)
                                            drawPath(path = trailPath, color = sentTrailColor)
                                        }
                                    }

                                    // Draw selection tint between A and B
                                    if (markerAOffset in 0..textLen &&
                                        markerBOffset in 0..textLen &&
                                        markerBOffset > markerAOffset
                                    ) {
                                        val selStart = markerAOffset
                                        val selEnd = markerBOffset
                                        val path = tlr.getPathForRange(selStart, selEnd)
                                        drawPath(path = path, color = selectionTintColor)
                                    }

                                    // Draw Marker A (green vertical bar at start of char)
                                    if (markerAOffset in 0..textLen) {
                                        val clampedA = markerAOffset.coerceAtMost(textLen)
                                        val rect: Rect = tlr.getCursorRect(clampedA)
                                        drawLine(
                                            color = markerAColor,
                                            start = Offset(rect.left, rect.top),
                                            end = Offset(rect.left, rect.bottom),
                                            strokeWidth = 3.dp.toPx()
                                        )
                                        // Draw small triangle indicator at top
                                        val triSize = 6.dp.toPx()
                                        drawRect(
                                            color = markerAColor,
                                            topLeft = Offset(rect.left, rect.top),
                                            size = Size(triSize, triSize * 0.6f)
                                        )
                                    }

                                    // Draw Marker B (red vertical bar at start of char)
                                    if (markerBOffset in 0..textLen) {
                                        val clampedB = markerBOffset.coerceAtMost(textLen)
                                        val rect: Rect = tlr.getCursorRect(clampedB)
                                        drawLine(
                                            color = markerBColor,
                                            start = Offset(rect.left, rect.top),
                                            end = Offset(rect.left, rect.bottom),
                                            strokeWidth = 3.dp.toPx()
                                        )
                                        // Draw small square indicator at top
                                        val sqSize = 6.dp.toPx()
                                        drawRect(
                                            color = markerBColor,
                                            topLeft = Offset(rect.left, rect.top),
                                            size = Size(sqSize, sqSize * 0.6f)
                                        )
                                    }
                                    
                                    // Draw Active Character Highlight
                                    if (activeCharIndex in 0 until textLen) {
                                        val rect = tlr.getBoundingBox(activeCharIndex)
                                        drawRect(
                                            color = activeCharColor,
                                            topLeft = Offset(rect.left, rect.top),
                                            size = Size(rect.width, rect.height)
                                        )
                                    }
                                }
                            ) {
                                if (value.isEmpty()) {
                                    Text(
                                        text = "Type or paste text here…",
                                        style = textStyle,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
        }
    }
}


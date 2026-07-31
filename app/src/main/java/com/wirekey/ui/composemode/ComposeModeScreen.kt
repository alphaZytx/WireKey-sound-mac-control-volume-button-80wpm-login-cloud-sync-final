package com.wirekey.ui.composemode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeModeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateBack: () -> Unit,
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

    val snackbarHostState = remember { SnackbarHostState() }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose Mode") }
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
            CodeEditorTextField(
                value = draftText,
                onValueChange = { viewModel.updateDraftText(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                readOnly = isSending
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${draftText.length} characters",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )

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
                    Text("Send to PC")
                }
            }
        }
    }
}

@Composable
fun CodeEditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false
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
            
            // Text Content
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScrollState)
                    .padding(16.dp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(
                            text = "Type here...",
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}

package com.wirekey.ui.livemode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wirekey.bluetooth.HidConnectionState
import com.wirekey.util.HidKeyCodes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveModeScreen(
    onNavigateBack: () -> Unit,
    viewModel: LiveModeViewModel = viewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(connectionState) {
        when (connectionState) {
            is HidConnectionState.Disconnected, is HidConnectionState.Error -> {
                onNavigateBack()
            }
            is HidConnectionState.Reconnecting -> {
                snackbarHostState.showSnackbar(
                    message = "Connection lost — reconnecting…",
                    duration = SnackbarDuration.Indefinite
                )
            }
            is HidConnectionState.Connected -> {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            else -> {}
        }
    }

    val connectedDeviceName = (connectionState as? HidConnectionState.Connected)?.device?.name ?: "Unknown Device"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Mode - $connectedDeviceName", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    TextButton(onClick = { viewModel.disconnect() }) {
                        Text("Disconnect")
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
            QuickAccessRow(viewModel)
            Spacer(modifier = Modifier.height(16.dp))
            TypingSurface(viewModel)
        }
    }
}

@Composable
fun QuickAccessRow(viewModel: LiveModeViewModel) {
    val isCtrlActive by viewModel.isCtrlActive.collectAsState()
    val isAltActive by viewModel.isAltActive.collectAsState()
    val isWinActive by viewModel.isWinActive.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SpecialKeyButton("Esc") { viewModel.sendSpecialKey(HidKeyCodes.KEY_ESCAPE) }
            SpecialKeyButton("Tab") { viewModel.sendSpecialKey(HidKeyCodes.KEY_TAB) }
            StickyModifierButton("Ctrl", isCtrlActive) { viewModel.toggleCtrl() }
            StickyModifierButton("Alt", isAltActive) { viewModel.toggleAlt() }
            StickyModifierButton("Win", isWinActive) { viewModel.toggleWin() }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Arrows cluster
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.sendSpecialKey(HidKeyCodes.KEY_LEFT_ARROW) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Left")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { viewModel.sendSpecialKey(HidKeyCodes.KEY_UP_ARROW) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up")
                    }
                    IconButton(
                        onClick = { viewModel.sendSpecialKey(HidKeyCodes.KEY_DOWN_ARROW) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down")
                    }
                }
                IconButton(onClick = { viewModel.sendSpecialKey(HidKeyCodes.KEY_RIGHT_ARROW) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Right")
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            SpecialKeyButton("Bksp") { viewModel.sendSpecialKey(HidKeyCodes.KEY_BACKSPACE) }
            SpecialKeyButton("Del") { viewModel.sendSpecialKey(HidKeyCodes.KEY_DELETE) }
            SpecialKeyButton("Enter") { viewModel.sendSpecialKey(HidKeyCodes.KEY_ENTER) }
        }
    }
}

@Composable
fun SpecialKeyButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
fun StickyModifierButton(label: String, isActive: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
fun TypingSurface(viewModel: LiveModeViewModel) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        contentAlignment = Alignment.TopStart
    ) {
        BasicTextField(
            value = text,
            onValueChange = { newValue ->
                val oldLen = text.length
                val newLen = newValue.length
                
                if (newLen > oldLen) {
                    // Characters were added (typed or pasted)
                    val added = newValue.substring(oldLen)
                    for (char in added) {
                        viewModel.sendCharacter(char)
                    }
                } else if (newLen < oldLen) {
                    // Characters were deleted (Backspace)
                    val diff = oldLen - newLen
                    repeat(diff) {
                        viewModel.sendSpecialKey(HidKeyCodes.KEY_BACKSPACE)
                    }
                }
                
                text = newValue
            },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrect = false
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
        )
        
        if (text.isEmpty()) {
            Text(
                text = "Tap here and start typing...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp
            )
        }
    }
    
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

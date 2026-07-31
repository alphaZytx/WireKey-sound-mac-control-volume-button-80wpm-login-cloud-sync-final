package com.wirekey.service

import android.content.Context
import android.content.Intent
import com.wirekey.WireKeyApp
import com.wirekey.bluetooth.HidReportBuilder
import com.wirekey.util.CadenceSettings
import com.wirekey.util.HidKeyCodes
import com.wirekey.util.TypingCadenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat

class TypingSessionManager(private val context: Context) {
    private val controller = WireKeyApp.hidKeyboardController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _sendProgress = MutableStateFlow(0f)
    val sendProgress: StateFlow<Float> = _sendProgress.asStateFlow()

    private val _remainingTimeMs = MutableStateFlow(0L)
    val remainingTimeMs: StateFlow<Long> = _remainingTimeMs.asStateFlow()


    private val _sendCompleteEvent = MutableSharedFlow<Unit>()
    val sendCompleteEvent: SharedFlow<Unit> = _sendCompleteEvent.asSharedFlow()

    private val _dynamicWpm = MutableStateFlow(60)
    val dynamicWpm: StateFlow<Int> = _dynamicWpm.asStateFlow()

    fun updateDynamicWpm(wpm: Int) {
        _dynamicWpm.value = wpm
    }

    private var sendJob: Job? = null
    private var sendGeneration = 0

    private var currentText: String = ""
    private var currentSettings: CadenceSettings = CadenceSettings.DEFAULT

    private fun startService() {
        try {
            val intent = Intent(context, KeystrokeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopService() {
        val intent = Intent(context, KeystrokeService::class.java)
        context.stopService(intent)
    }

    /**
     * Send a single HID key press (down + up) with the given key code
     * and optional modifier bits.
     */
    private suspend fun sendKey(keyCode: Int, modifiers: Int = 0, multiplier: Float = 1.0f) {
        val down = HidReportBuilder.keyDownReport(keyCode, modifiers)
        val up = HidReportBuilder.keyUpReport()
        controller.sendReport(down)
        delay((12 * multiplier).toLong())
        controller.sendReport(up)
        delay((12 * multiplier).toLong())
    }

    // clearAutoIndent removed in favor of Smart Indent Recalculation

    fun sendToPc(textToSend: String, settings: CadenceSettings) {
        if (textToSend.isEmpty()) return

        currentText = textToSend
        currentSettings = settings

        val thisGeneration = ++sendGeneration

        _isSending.value = true
        _isPaused.value = false
        _sendProgress.value = 0f
        _remainingTimeMs.value = 0L
        _dynamicWpm.value = settings.targetWpm
        
        startService()

        val oldJob = sendJob
        sendJob = scope.launch(Dispatchers.IO) {
            oldJob?.cancelAndJoin()
            try {
                val plan = TypingCadenceEngine.generateTypingPlan(textToSend, settings)
                val totalSteps = plan.size

                if (totalSteps == 0) {
                    _sendCompleteEvent.emit(Unit)
                    return@launch
                }

                val estimatedTimes = plan.map { keystroke ->
                    var ms = keystroke.delayMs
                    if (keystroke.isBackspace) {
                        ms += 30L // ~15ms down, ~15ms up delay approximation
                    } else {
                        val char = keystroke.char
                        if (char != null) {
                            ms += 30L // down/up delay approximation
                            if (settings.codeEditorMode) {
                                if (char == '{' || char == '(' || char == '[') ms += 80L + 24L
                                if (char == '\n') ms += 30L + 72L
                            }
                        }
                    }
                    ms
                }
                var totalRemaining = estimatedTimes.sum()
                _remainingTimeMs.value = totalRemaining

                var i = 0
                while (i < plan.size) {
                    val keystroke = plan[i]

                    while (_isPaused.value) { delay(50) }

                    val currentMultiplier = settings.targetWpm.toFloat() / _dynamicWpm.value.coerceAtLeast(1).toFloat()

                    if (keystroke.delayMs > 0) { delay((keystroke.delayMs * currentMultiplier).toLong()) }

                    while (_isPaused.value) { delay(50) }

                    if (keystroke.isBackspace) {
                        var backspaceCount = 1
                        var j = i + 1
                        while (j < plan.size && plan[j].isBackspace) {
                            backspaceCount++
                            j++
                        }
                        
                        
                        executeBackspaces(backspaceCount, currentMultiplier)
                        
                        for (consumedIdx in i until i + backspaceCount) {
                            totalRemaining -= estimatedTimes[consumedIdx]
                            _remainingTimeMs.value = (totalRemaining.coerceAtLeast(0L) * currentMultiplier).toLong()
                            _sendProgress.value = (consumedIdx + 1).toFloat() / totalSteps
                        }
                        i += backspaceCount
                    } else {
                        val char = keystroke.char
                        if (char != null) {
                            val reports = HidReportBuilder.charToReports(char)
                            if (reports != null) {
                                val (down, up) = reports
                                controller.sendReport(down)
                                delay((15 * currentMultiplier).toLong())
                                controller.sendReport(up)
                            }
                            // ── Code-editor-only workarounds ──────────────
                            if (settings.codeEditorMode) {
                                // Code editors auto-close brackets: typing {
                                // inserts {} with cursor between them. Delete
                                // the auto-inserted closing bracket.
                                if (char == '{' || char == '(' || char == '[') {
                                    delay((80 * currentMultiplier).toLong()) // increased to 80ms to allow web editors (CodeChef) time to insert the bracket
                                    sendKey(HidKeyCodes.KEY_DELETE, 0, currentMultiplier)
                                }

                                // The TypingCadenceEngine now handles Smart Indent Recalculation
                                // directly in the plan, so we no longer need to manually clear auto-indent here!
                            }
                        }
                        totalRemaining -= estimatedTimes[i]
                        _remainingTimeMs.value = (totalRemaining.coerceAtLeast(0L) * currentMultiplier).toLong()
                        _sendProgress.value = (i + 1).toFloat() / totalSteps
                        i++
                    }
                }
                _sendCompleteEvent.emit(Unit)
            } catch (e: CancellationException) {
                // Cancelled
            } finally {
                if (sendGeneration == thisGeneration) {
                    _isSending.value = false
                    _isPaused.value = false
                    _sendProgress.value = 0f
                    _remainingTimeMs.value = 0L
                    stopService()
                }
            }
        }
    }

    fun cancelSend() {
        sendJob?.cancel()
        sendJob = null
        _isSending.value = false
        _isPaused.value = false
        _sendProgress.value = 0f
        _remainingTimeMs.value = 0L
        stopService()
    }

    fun pauseSend() {
        _isPaused.value = true
    }

    fun resumeSend() {
        _isPaused.value = false
    }

    fun restartSend() {
        if (currentText.isNotEmpty()) {
            sendToPc(currentText, currentSettings)
        }
    }

    private suspend fun executeBackspaces(count: Int, multiplier: Float = 1.0f) {
        if (count <= 0) return
        
        val down = HidReportBuilder.keyDownReport(HidKeyCodes.KEY_BACKSPACE)
        val up = HidReportBuilder.keyUpReport()

        if (count < 4) {
            // Threshold: discrete tapping with deceleration
            for (k in 0 until count) {
                while (_isPaused.value) { delay(50) }
                
                controller.sendReport(down)
                delay((15 * multiplier).toLong())
                controller.sendReport(up)
                
                if (k < count - 1) {
                    val waitTime = if (k == count - 2) {
                        // Deceleration: Final tap is delayed for visual confirmation
                        (kotlin.random.Random.nextLong(300, 400) * multiplier).toLong()
                    } else {
                        // Middle taps: Very fast muscle-memory tapping
                        (kotlin.random.Random.nextLong(100, 150) * multiplier).toLong()
                    }
                    
                    // Break the delay into chunks to stay responsive to pauses
                    var elapsed = 0L
                    while (elapsed < waitTime) {
                        while (_isPaused.value) { delay(50) }
                        val chunk = minOf(50L, waitTime - elapsed)
                        delay(chunk)
                        elapsed += chunk
                    }
                }
            }
        } else {
            // Holding Trigger: Simulate OS-level hold
            // 1st backspace (discrete tap)
            while (_isPaused.value) { delay(50) }
            controller.sendReport(down)
            delay((15 * multiplier).toLong())
            controller.sendReport(up)
            
            // Wait for initial OS repeat delay: strictly ~500ms (480-520ms)
            val repeatDelay = (kotlin.random.Random.nextLong(480, 520) * multiplier).toLong()
            var elapsed = 0L
            while (elapsed < repeatDelay) {
                while (_isPaused.value) { delay(50) }
                val chunk = minOf(50L, repeatDelay - elapsed)
                delay(chunk)
                elapsed += chunk
            }
            
            // Remaining (N - 1) backspaces at a highly consistent robotic repeat rate (~30ms cycle)
            for (k in 1 until count) {
                while (_isPaused.value) { delay(50) }
                
                controller.sendReport(down)
                delay((15 * multiplier).toLong())
                controller.sendReport(up)
                
                val rate = (kotlin.random.Random.nextLong(13, 17) * multiplier).toLong() // 15 + ~15 = ~30ms total cycle
                delay(rate)
            }
        }
    }

    companion object {
        private const val TAB_WIDTH = 4
    }
}

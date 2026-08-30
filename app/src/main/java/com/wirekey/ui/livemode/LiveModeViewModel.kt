package com.wirekey.ui.livemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wirekey.WireKeyApp
import com.wirekey.bluetooth.HidConnectionState
import com.wirekey.bluetooth.HidReportBuilder
import com.wirekey.util.HidKeyCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val KEY_HOLD_MS = 15L
private const val CURSOR_MOVE_HOLD_MS = 12L

class LiveModeViewModel : ViewModel() {
    private val controller = WireKeyApp.hidKeyboardController

    // Click playback is triggered from *inside* the queued actions below, next to the
    // report it belongs to, rather than when an action is enqueued: submit() returns
    // immediately, so firing there would play a whole burst of clicks up front while the
    // reports they belong to were still draining out over Bluetooth.
    private val acoustics = WireKeyApp.acousticTypingEngine

    val connectionState: StateFlow<HidConnectionState> = controller.connectionState

    private val _isCtrlActive = MutableStateFlow(false)
    val isCtrlActive: StateFlow<Boolean> = _isCtrlActive.asStateFlow()

    private val _isAltActive = MutableStateFlow(false)
    val isAltActive: StateFlow<Boolean> = _isAltActive.asStateFlow()

    private val _isWinActive = MutableStateFlow(false)
    val isWinActive: StateFlow<Boolean> = _isWinActive.asStateFlow()

    fun toggleCtrl() { _isCtrlActive.value = !_isCtrlActive.value }
    fun toggleAlt() { _isAltActive.value = !_isAltActive.value }
    fun toggleWin() { _isWinActive.value = !_isWinActive.value }

    // Tracks where we *believe* the remote host's text cursor sits, in characters from
    // the start of the text this screen has been mirroring. Updated synchronously (not
    // inside the queued suspend blocks below) every time an action is enqueued, so it
    // always reflects the *planned* end state once the queue drains — which is what the
    // next call needs to compute a correct delta against, even before earlier queued
    // reports have actually gone out over Bluetooth.
    //
    // Left/Right/Backspace/Enter from the quick-access row keep this in sync too since
    // their effect on a linear cursor index is deterministic. Up/Down are NOT tracked:
    // where the cursor lands depends on the host app's line-wrapping, which we have no
    // way to know over a one-way HID link — using them may leave this position stale
    // until the next full text-field edit resyncs it.
    private var remoteCursorPos = 0

    // Every key report is funneled through this single-consumer queue so reports for
    // rapid, back-to-back actions (fast typing, a paste, a big cursor jump) go out
    // strictly in the order they were requested. Without this, each action firing its
    // own independent coroutine could interleave at their internal delay() suspension
    // points — e.g. a second key's "down" report slipping out before the first key's
    // "up" report — corrupting both key order and, once repositioning was in the mix,
    // where the remote cursor actually ends up.
    private val actionQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (action in actionQueue) {
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad report shouldn't wedge the queue for the rest of the session.
                }
            }
        }
    }

    private fun submit(action: suspend () -> Unit) {
        actionQueue.trySend(action)
    }

    private suspend fun pressKeyRepeated(keyCode: Int, times: Int, holdMs: Long = CURSOR_MOVE_HOLD_MS) {
        repeat(times) {
            acoustics.onKeyCodeDown(keyCode)
            controller.sendReport(HidReportBuilder.keyDownReport(keyCode))
            delay(holdMs)
            controller.sendReport(HidReportBuilder.keyUpReport())
            delay(holdMs)
        }
    }

    private fun consumeStickyModifiers(): Int {
        var modifiers = 0
        if (_isCtrlActive.value) modifiers = modifiers or HidKeyCodes.MODIFIER_LEFT_CTRL
        if (_isAltActive.value) modifiers = modifiers or HidKeyCodes.MODIFIER_LEFT_ALT
        if (_isWinActive.value) modifiers = modifiers or HidKeyCodes.MODIFIER_LEFT_GUI
        _isCtrlActive.value = false
        _isAltActive.value = false
        _isWinActive.value = false
        return modifiers
    }

    fun disconnect() {
        val state = connectionState.value
        if (state is HidConnectionState.Connected) {
            controller.disconnect(state.device)
        }
    }

    fun sendCharacter(char: Char) {
        val reports = HidReportBuilder.charToReports(char) ?: return
        val extraModifiers = consumeStickyModifiers()
        val downReport = reports.first
        downReport[0] = (downReport[0].toInt() or extraModifiers).toByte()

        submit {
            acoustics.onCharacterDown(char)
            controller.sendReport(downReport)
            delay(KEY_HOLD_MS)
            controller.sendReport(reports.second)
            delay(KEY_HOLD_MS)
        }
        remoteCursorPos++
    }

    fun sendSpecialKey(keyCode: Int) {
        val extraModifiers = consumeStickyModifiers()

        submit {
            acoustics.onKeyCodeDown(keyCode)
            controller.sendReport(HidReportBuilder.keyDownReport(keyCode, extraModifiers))
            delay(KEY_HOLD_MS)
            controller.sendReport(HidReportBuilder.keyUpReport())
            delay(KEY_HOLD_MS)
        }

        when (keyCode) {
            HidKeyCodes.KEY_LEFT_ARROW, HidKeyCodes.KEY_BACKSPACE ->
                remoteCursorPos = (remoteCursorPos - 1).coerceAtLeast(0)
            HidKeyCodes.KEY_RIGHT_ARROW, HidKeyCodes.KEY_ENTER ->
                remoteCursorPos++
            // KEY_DELETE (forward delete) doesn't move the cursor.
            // KEY_UP_ARROW / KEY_DOWN_ARROW are intentionally left untracked — see the
            // remoteCursorPos doc comment above.
        }
    }

    /**
     * Mirrors a local text-field edit to the remote host: figures out what actually
     * changed between [oldText] and [newText] via a common-prefix/common-suffix diff
     * (robust to edits anywhere in the string, not just at the end), repositions the
     * remote cursor to that spot with Left/Right arrow presses if it isn't already
     * there, replays the edit as backspaces + typed characters, and — if the local
     * caret ends up somewhere a plain edit wouldn't naturally land it (autocorrect,
     * predictive text) — repositions once more to match.
     *
     * If [oldText] and [newText] are identical, this is a pure caret move (e.g. the
     * user tapped elsewhere without typing) and only a reposition is sent.
     */
    fun applyLocalEdit(oldText: String, newText: String, newCursorPos: Int) {
        val target = newCursorPos.coerceIn(0, newText.length)

        if (oldText == newText) {
            moveRemoteCursor(target)
            return
        }

        val minLen = minOf(oldText.length, newText.length)
        var prefixLen = 0
        while (prefixLen < minLen && oldText[prefixLen] == newText[prefixLen]) prefixLen++

        var suffixLen = 0
        while (suffixLen < minLen - prefixLen &&
            oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]
        ) suffixLen++

        val removedCount = oldText.length - prefixLen - suffixLen
        val addedText = newText.substring(prefixLen, newText.length - suffixLen)
        val editEndInOld = oldText.length - suffixLen // == prefixLen + removedCount

        val moveDelta = editEndInOld - remoteCursorPos
        remoteCursorPos = editEndInOld // plan: cursor will be here once repositioned

        submit {
            if (moveDelta != 0) {
                pressKeyRepeated(
                    keyCode = if (moveDelta > 0) HidKeyCodes.KEY_RIGHT_ARROW else HidKeyCodes.KEY_LEFT_ARROW,
                    times = kotlin.math.abs(moveDelta)
                )
            }
            pressKeyRepeated(HidKeyCodes.KEY_BACKSPACE, removedCount, holdMs = KEY_HOLD_MS)
            for (char in addedText) {
                val reports = HidReportBuilder.charToReports(char) ?: continue
                acoustics.onCharacterDown(char)
                controller.sendReport(reports.first)
                delay(KEY_HOLD_MS)
                controller.sendReport(reports.second)
                delay(KEY_HOLD_MS)
            }
        }
        remoteCursorPos = prefixLen + addedText.length // plan: cursor after backspacing + typing

        if (target != remoteCursorPos) {
            moveRemoteCursor(target)
        }
    }

    private fun moveRemoteCursor(target: Int) {
        val delta = target - remoteCursorPos
        if (delta == 0) return
        val keyCode = if (delta > 0) HidKeyCodes.KEY_RIGHT_ARROW else HidKeyCodes.KEY_LEFT_ARROW
        val count = kotlin.math.abs(delta)
        submit { pressKeyRepeated(keyCode, count) }
        remoteCursorPos = target
    }
}

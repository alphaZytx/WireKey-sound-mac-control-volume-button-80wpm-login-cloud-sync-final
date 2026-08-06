package com.wirekey.ui.composemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wirekey.WireKeyApp
import com.wirekey.data.SentMessage
import com.wirekey.util.CadenceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which marker the user is currently about to place.
 * [NONE]  = normal editing mode (no placement pending).
 * [START] = next tap in the text field will drop the Start marker.
 * [END]   = next tap in the text field will drop the End marker.
 */
enum class MarkerPlacementMode { NONE, START, END }

class ComposeModeViewModel : ViewModel() {
    private val controller = WireKeyApp.hidKeyboardController
    private val settingsRepo = WireKeyApp.settingsRepository

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    val connectionState = controller.connectionState
    private val typingManager = WireKeyApp.typingSessionManager

    val isSending = typingManager.isSending
    val isPaused = typingManager.isPaused
    val sendProgress = typingManager.sendProgress
    val remainingTimeMs = typingManager.remainingTimeMs
    val sendCompleteEvent = typingManager.sendCompleteEvent
    val dynamicWpm = typingManager.dynamicWpm

    // ── Partial Text Execution markers ──────────────────────────────────────
    // Character offsets into draftText; -1 means "not placed / use default".
    // Default: start = 0 (beginning of text), end = text.length (end of text).
    // A user-placed markerA overrides the start; a user-placed markerB overrides the end.
    private val _markerA = MutableStateFlow(-1)
    val markerA: StateFlow<Int> = _markerA.asStateFlow()

    private val _markerB = MutableStateFlow(-1)
    val markerB: StateFlow<Int> = _markerB.asStateFlow()

    /**
     * The active placement mode. When non-NONE the text field intercepts the
     * next tap to drop the corresponding marker instead of moving the cursor.
     */
    private val _placementMode = MutableStateFlow(MarkerPlacementMode.NONE)
    val placementMode: StateFlow<MarkerPlacementMode> = _placementMode.asStateFlow()

    /**
     * True when at least one marker is explicitly placed by the user.
     * In this state the send action will only type the selected range.
     */
    val partialExecutionEnabled: StateFlow<Boolean> = combine(
        _markerA, _markerB
    ) { a, b ->
        a >= 0 || b >= 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val activeCharIndex: StateFlow<Int> = combine(
        typingManager.activeCharIndex,
        _markerA
    ) { activeIndex, startMarker ->
        if (activeIndex < 0) -1 else activeIndex + kotlin.math.max(0, startMarker)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    // ── Sent Text Trail (permanent record) ────────────────────────────────────
    // Each completed or cancelled send commits the exact [start, end] range that
    // was actually left on the host, using typingManager.lastSentCharIndex — a
    // dedicated counter that already retreats on backspace corrections and is
    // fully decoupled from activeCharIndex's transient cursor-highlight resets.
    // The start offset is captured once, when the send begins, and never re-read
    // afterwards — so moving markers or toggling placement mode for the *next*
    // selection can never retroactively shift or clear an already-shaded range.
    private val _sentTrailRanges = MutableStateFlow<List<IntRange>>(emptyList())

    // Absolute (draft-relative) offset where the in-flight send started; -1 when idle.
    private val _pendingTrailStart = MutableStateFlow(-1)

    /** Committed history + the in-progress send's growing range, ready for the UI to draw. */
    val displayedTrailRanges: StateFlow<List<IntRange>> = combine(
        _sentTrailRanges, _pendingTrailStart, typingManager.lastSentCharIndex
    ) { committed, start, lastSentRelative ->
        if (start >= 0 && lastSentRelative > 0) {
            committed + IntRange(start, start + lastSentRelative)
        } else {
            committed
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Observe settings from DataStore dynamically
    val cadenceSettings: StateFlow<CadenceSettings> = combine(
        settingsRepo.targetWpm,
        settingsRepo.humanTypingEnabled,
        settingsRepo.naturalTypingEnabled,
        settingsRepo.codeEditorMode
    ) { wpm, humanEnabled, natural, codeEditor ->
        CadenceSettings(
            targetWpm = wpm,
            humanTypingEnabled = humanEnabled,
            naturalTypingEnabled = natural,
            codeEditorMode = codeEditor
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CadenceSettings.DEFAULT)

    val sendHistory: StateFlow<List<SentMessage>> = settingsRepo.sendHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Record every message that finishes sending (not ones that were cancelled
        // mid-way — sendCompleteEvent only fires on genuine completion).
        // We record the *actual text that was sent*, which may be just the selected
        // substring when partial execution is active.
        viewModelScope.launch {
            typingManager.sendCompleteEvent.collect {
                val sentText = _lastSentText
                if (sentText.isNotBlank()) {
                    settingsRepo.addToHistory(sentText)
                }
            }
        }

        // Freeze this send's start the instant it begins, then fold the range it
        // actually covered into the permanent trail history the instant it ends
        // (completion or cancellation) — read directly from lastSentCharIndex's
        // current value, not a copy tracked in parallel, so there's no race with
        // its own StateFlow delivery.
        viewModelScope.launch {
            isSending.collect { sending ->
                if (sending) {
                    _pendingTrailStart.value = _markerA.value.coerceAtLeast(0)
                } else {
                    val start = _pendingTrailStart.value
                    val lastSentRelative = typingManager.lastSentCharIndex.value
                    if (start >= 0 && lastSentRelative > 0) {
                        _sentTrailRanges.value = _sentTrailRanges.value + IntRange(start, start + lastSentRelative)
                    }
                    _pendingTrailStart.value = -1
                }
            }
        }
    }

    /** Holds the text that was most recently dispatched to the typing engine. */
    private var _lastSentText: String = ""

    // ── Placement mode helpers ───────────────────────────────────────────────

    /**
     * Activates START placement mode. A subsequent tap inside the text field
     * will drop the Start marker at that exact cursor position.
     * Calling again while START is already active will cancel/toggle off.
     */
    fun activateStartPlacement() {
        _placementMode.value = if (_placementMode.value == MarkerPlacementMode.START) {
            MarkerPlacementMode.NONE
        } else {
            MarkerPlacementMode.START
        }
    }

    /**
     * Activates END placement mode. A subsequent tap inside the text field
     * will drop the End marker at that exact cursor position.
     * Calling again while END is already active will cancel/toggle off.
     */
    fun activateEndPlacement() {
        _placementMode.value = if (_placementMode.value == MarkerPlacementMode.END) {
            MarkerPlacementMode.NONE
        } else {
            MarkerPlacementMode.END
        }
    }

    /**
     * Called by the text field when the user taps while a placement mode is active.
     * Drops the appropriate marker at [offset] and returns to NONE mode.
     * If placement mode is NONE, this is a no-op (normal cursor placement).
     */
    fun onTextFieldTapForPlacement(offset: Int) {
        when (_placementMode.value) {
            MarkerPlacementMode.START -> setMarkerA(offset)
            MarkerPlacementMode.END   -> setMarkerB(offset)
            MarkerPlacementMode.NONE  -> { /* normal tap — do nothing */ }
        }
        _placementMode.value = MarkerPlacementMode.NONE
    }

    // ── Marker helpers ───────────────────────────────────────────────────────

    /**
     * Place or move Marker A (Start) to [offset] in the current draft.
     * Clamps to valid range. If the new A would be >= B, B is cleared.
     */
    fun setMarkerA(offset: Int) {
        val clamped = offset.coerceIn(0, _draftText.value.length)
        _markerA.value = clamped
        // If A is now at or past B, invalidate B so the pair stays consistent.
        if (_markerB.value in 0..clamped) {
            _markerB.value = -1
        }
    }

    /**
     * Place or move Marker B (End) to [offset] in the current draft.
     * Clamped to valid range. B must come strictly after A (or A not yet placed).
     */
    fun setMarkerB(offset: Int) {
        val clamped = offset.coerceIn(0, _draftText.value.length)
        val a = _markerA.value
        // B must come after A (or A not yet placed — allow placement anyway).
        if (a < 0 || clamped > a) {
            _markerB.value = clamped
        }
    }

    /** Remove both markers, cancel any active placement mode, and return to full-text execution. */
    fun clearMarkers() {
        _markerA.value = -1
        _markerB.value = -1
        _placementMode.value = MarkerPlacementMode.NONE
    }

    // ── Draft helpers ────────────────────────────────────────────────────────

    fun updateDraftText(text: String) {
        _draftText.value = text
        // Clamp or invalidate markers when the text length shrinks.
        val len = text.length
        if (_markerA.value > len) _markerA.value = len
        if (_markerB.value > len) _markerB.value = len
        // Re-validate: A must still be strictly less than B.
        if (_markerA.value >= 0 && _markerB.value >= 0 && _markerA.value >= _markerB.value) {
            _markerB.value = -1
        }
        // Trail ranges behave like SPAN_EXCLUSIVE_EXCLUSIVE spans: when the user
        // manually deletes text, clamp each range's boundaries down to the new
        // (shorter) length so the shading is trimmed along with it, and drop any
        // range that's been entirely deleted.
        if (_sentTrailRanges.value.isNotEmpty()) {
            _sentTrailRanges.value = _sentTrailRanges.value.mapNotNull { range ->
                val newStart = range.first.coerceAtMost(len)
                val newEnd = range.last.coerceAtMost(len)
                if (newEnd > newStart) newStart..newEnd else null
            }
        }
    }

    /** The only manual way to wipe the sent-text shading: clearing the draft entirely. */
    fun clearDraftText() {
        _draftText.value = ""
        clearMarkers()
        _sentTrailRanges.value = emptyList()
        _pendingTrailStart.value = -1
    }

    /** Populates the draft box with a past message. Blocked mid-send (unless paused) to avoid clobbering it. */
    fun restoreFromHistory(text: String) {
        if (isSending.value && !isPaused.value) return
        _draftText.value = text
        clearMarkers()
    }

    /** Loads a past message into the draft box and sends it immediately. Requires being fully idle. */
    fun resendFromHistory(text: String) {
        if (isSending.value) return
        _draftText.value = text
        // History resends always use the full text — clear any stale markers.
        clearMarkers()
        _lastSentText = text
        typingManager.sendToPc(text, cadenceSettings.value)
    }

    fun deleteHistoryEntry(index: Int) {
        viewModelScope.launch { settingsRepo.removeHistoryEntry(index) }
    }

    fun clearHistory() {
        viewModelScope.launch { settingsRepo.clearHistory() }
    }

    /**
     * Returns the text that should actually be typed based on marker state:
     *
     * | markerA | markerB | Text sent                              |
     * |---------|---------|----------------------------------------|
     * |   -1    |   -1    | Full draft (0 → end)                   |
     * |  >= 0   |   -1    | From markerA to end of draft           |
     * |   -1    |  >= 0   | From 0 to markerB                      |
     * |  >= 0   |  >= 0   | Substring draft[markerA, markerB)      |
     */
    private fun getTextToSend(): String {
        val draft = _draftText.value
        val a = _markerA.value
        val b = _markerB.value
        return when {
            a >= 0 && b > a && b <= draft.length -> draft.substring(a, b)       // both set: A→B
            a >= 0 && b < 0 && a < draft.length  -> draft.substring(a)          // start only: A→end
            a < 0 && b >= 0 && b <= draft.length -> draft.substring(0, b)       // end only: 0→B
            else                                  -> draft                        // no markers: full text
        }
    }

    fun sendToPc() {
        val textToSend = getTextToSend()
        if (textToSend.isEmpty()) return
        _lastSentText = textToSend
        val settings = cadenceSettings.value
        typingManager.sendToPc(textToSend, settings)
    }

    fun cancelSend() {
        typingManager.cancelSend()
    }

    fun pauseSend() {
        typingManager.pauseSend()
    }

    fun resumeSend() {
        typingManager.resumeSend()
    }

    fun restartSend() {
        typingManager.restartSend()
    }

    fun updateDynamicWpm(wpm: Float) {
        typingManager.updateDynamicWpm(wpm.toInt())
    }
}

# Live Execution Tracker Implementation Plan

This feature adds a real-time visual highlight to the character currently being sent by `TypingSessionManager`, and auto-scrolls the `CodeEditorTextField` to keep the active character visible.

## Proposed Changes

### `com.wirekey.humantyping.MarkovTyper.kt`
- **Modify** `TypingEvent` data class to include `val mentalCursorPos: Int`.
- **Update** instances of `TypingEvent` creation to pass the current `mentalCursorPos`. This exactly identifies the index in the original text (or sub-text) that the event relates to.

### `com.wirekey.util.TypingCadenceEngine.kt`
- **Modify** `PlannedKeystroke` data class to include `val originalIndex: Int? = null`.
- **Update** `generateHumanPlan` to pass `event.mentalCursorPos` to the created `PlannedKeystroke`s.
- **Update** `generateSimplePlan` to pass `i` to the created `PlannedKeystroke`s.

### `com.wirekey.service.TypingSessionManager.kt`
- **Add** `_activeCharIndex` (MutableStateFlow) and `activeCharIndex` (StateFlow) starting at -1.
- **Update** the main loop in `sendToPc` to set `_activeCharIndex.value = keystroke.originalIndex` right before the `delay(...)`.
- **Reset** `_activeCharIndex.value = -1` on completion or cancellation.

### `com.wirekey.ui.composemode.ComposeModeViewModel.kt`
- **Add** `activeCharIndex` StateFlow that combines `typingManager.activeCharIndex` with `markerA`. It translates the local index of `textToSend` back to the global index in `draftText` by adding `maxOf(0, markerA)`.

### `com.wirekey.ui.composemode.ComposeModeScreen.kt`
- **Update** `CodeEditorTextField` to accept `activeCharIndex: Int`.
- **Add** drawing logic in `drawBehind` or `decorationBox` to draw a background rectangle (e.g., `Color.Yellow.copy(alpha=0.4f)`) behind the `activeCharIndex`. We use `textLayoutResult.getBoundingBox(activeCharIndex)` to get the bounds.
- **Implement** auto-scrolling: Use `Modifier.onGloballyPositioned` to measure the viewport dimensions. Add a `LaunchedEffect(activeCharIndex)` that animates `verticalScrollState` and `horizontalScrollState` so that the bounding box of the active character remains within the visible area.

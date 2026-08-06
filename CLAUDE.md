# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

WireKey (also published as "AutoTyper") is a single-module Android app that turns a phone into a Bluetooth HID keyboard using Android's native `BluetoothHidDevice` API — no software is required on the host PC/Mac/Linux/iPadOS/ChromeOS machine. Package: `com.wirekey`. Min SDK 28 (API 28 is required for `BluetoothHidDevice`), target/compile SDK 34. UI is 100% Jetpack Compose with Navigation Compose (Kotlin 2.0, `org.jetbrains.kotlin.plugin.compose`).

The app's differentiating feature is the **Human Typing Engine**: a Markov-chain typing simulator (ported from a Python `HumanTyping` library) that reproduces human keystroke timing, mistakes, and corrections closely enough to defeat keystroke-cadence anti-cheat/analysis systems. Most non-trivial logic in this repo lives in that engine.

## Build & test commands

There is no committed `gradlew` wrapper script (only `gradle/wrapper/gradle-wrapper.properties`) and no `gradle` binary on this machine's PATH — in practice this project is built/run through Android Studio. If you need CLI builds, regenerate the wrapper first (`gradle wrapper`) using a locally installed Gradle, then:

```bash
./gradlew assembleDebug          # build debug APK
./gradlew test                   # run JVM unit tests (app/src/test)
./gradlew connectedAndroidTest   # run instrumented tests (app/src/androidTest) — needs a device/emulator
./gradlew test --tests "com.wirekey.util.TypingCadenceEngineTest"   # single unit test class
```

Unit tests (fast, no device): `app/src/test/java/com/wirekey/...` — currently `HidReportBuilderTest` and `TypingCadenceEngineTest`.
Instrumented tests (need a device/emulator): `app/src/androidTest/java/com/wirekey/...` — currently `PermissionManagerTest`.

## Architecture

**App-scoped singletons** live in `WireKeyApp` (`Application` subclass, `app/src/main/java/com/wirekey/WireKeyApp.kt`) and are accessed statically as `WireKeyApp.hidKeyboardController`, `WireKeyApp.settingsRepository`, `WireKeyApp.typingSessionManager`. There is no DI framework — ViewModels and services just reach into these companion-object singletons directly.

**Navigation** (`ui/WireKeyNavigation.kt`) is a flat 4-screen graph: `pairing` (start) → `live_mode` / `compose_mode`, plus `settings` reachable from either. Each screen has a `XScreen.kt` (Compose UI) + `XViewModel.kt` pair under `ui/<feature>/`.

**Bluetooth HID layer** (`bluetooth/`):
- `HidKeyboardController` owns the `BluetoothHidDevice` profile proxy, registers the raw HID report descriptor (hardcoded hex bytes for a standard 8-key-rollover keyboard, `hidKeyboardDescriptor()`), and exposes connection state as a `StateFlow<HidConnectionState>` sealed class (`Unregistered/Registered/Connecting/Reconnecting/Connected/Disconnected/Error`). It self-manages reconnection with a fixed backoff ladder (`startReconnectionLoop`) and starts/stops `ConnectionKeepAliveService` (a foreground service) around the connected lifetime to survive OS process death.
- `HidReportBuilder` converts characters/keycodes into the raw HID report byte arrays sent via `controller.sendReport(...)`.
- `PermissionManager` centralizes the runtime Bluetooth/location permission checks described in the README.
- **Important gotcha (from README):** if you modify the HID descriptor, the host OS caches the old one per-device. You must "Remove Device" and re-pair on the host for changes to take effect — this is not a bug in the app.

**Two typing modes, one dispatch path:**
- *Live Mode* (`ui/livemode/`) streams keystrokes from the phone's own keyboard input in real time.
- *Compose Mode* (`ui/composemode/`) takes a full block of text and plays it back through the Human Typing Engine.
- Both ultimately go through `service.TypingSessionManager`, which turns a `List<PlannedKeystroke>` (from `TypingCadenceEngine`) into a real-time sequence of `controller.sendReport()` calls with per-keystroke delays, pause/resume/cancel/restart support, and dynamic WPM-based speed retiming mid-send. `KeystrokeService` is the foreground service kept alive while a send is in progress (separate from `ConnectionKeepAliveService`, which covers the Bluetooth connection itself).

**Human Typing Engine** (`humantyping/` + `util/TypingCadenceEngine.kt`) — the core pipeline is:
1. `MarkovTyper.run()` (`humantyping/MarkovTyper.kt`) simulates a full typing session for a string at a target WPM and returns a `List<TypingEvent>` (`TYPED` / `TYPED_ERROR` / `TYPED_SWAP` / `BACKSPACE`), using `KeyboardLayout` (key adjacency/travel distance) and `LanguageModel` (bigram frequency / word difficulty) to drive Gaussian per-keystroke timing, neighbor-key typos, anticipation swaps, and cognitive-delay-gated backspace corrections. `HumanTypingConfig` holds the tunable constants (default WPM, probabilities, etc.).
2. `TypingCadenceEngine.generateTypingPlan()` (`util/TypingCadenceEngine.kt`) wraps that simulation (or a simpler uniform-delay fallback when `humanTypingEnabled = false`) and layers on Android/IDE-specific post-processing: `applySyntaxDeceleration` (slows down for symbols like `->`, `::`, `!=`), `simulateAutoIndentAndTrim` (predicts IDE auto-indent after Enter and trims to avoid corrupting code formatting), and `applyWpmCalibration`. Output is a flat `List<PlannedKeystroke>` (char-or-backspace + delay) that `TypingSessionManager` executes.
3. `CadenceSettings` is the single config object threaded through this whole pipeline (target WPM, human-typing on/off, variability, code-editor-mode). It's persisted via `data/SettingsRepository` (DataStore Preferences).

When changing typing behavior, decide which layer it belongs in: physiological/error-simulation realism → `MarkovTyper`/`LanguageModel`/`KeyboardLayout`; editor/IDE-specific formatting workarounds (bracket auto-close deletion, auto-indent) → `TypingCadenceEngine` or `TypingSessionManager`'s `codeEditorMode` branches, not the Markov model.

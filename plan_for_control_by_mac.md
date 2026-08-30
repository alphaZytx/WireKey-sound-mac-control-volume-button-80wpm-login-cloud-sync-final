# Plan — Control WireKey from the Mac, Bluetooth only, no Mac software

**Goal.** A key on the Mac drives the Compose Mode send button remotely:

| Phone state | The trigger does |
|---|---|
| Idle (not sending) | **Start Sending Keystrokes** |
| Sending | **Pause** |
| Paused | **Resume** |

**Your constraints, as agreed:**
- **Bluetooth only.** No Wi-Fi, no LAN, no network sockets.
- **Nothing installed or running on the Mac.** No helper app, no Automator action, no Karabiner.
- **The trigger must not hamper any other feature** — not WireKey's, and not normal Mac usage.
- Start only needs to work from the Compose screen. Pause/Resume work app-wide.
- Every existing feature stays behaviourally identical. Feature off by default.
- **No HID descriptor change** ⇒ no re-pairing (§1.2).

---

## 0. Read this first: the trigger cannot be F8, or any key you choose

You said any key is fine. Unfortunately the channel constrains this much harder than "any key".

The phone is a Bluetooth HID **device**. HID key data is strictly one-directional, **device → host**.
Pressing a key on the Mac delivers it to the Mac's focused app; **nothing goes back down the link.**
With no software on the Mac, there is exactly **one** thing the Mac sends to the phone unprompted:
the **HID LED output report** — the lock-key state that a host pushes to every connected keyboard so
their Caps/Num/Scroll-Lock lights stay in sync.

That report carries 5 bits and nothing else. So the trigger must be a key macOS treats as a **lock
key**, and on a Mac that is **Caps Lock**:

- **Num Lock** — Apple keyboards have `Clear` where Num Lock would be, and macOS maintains no
  num-lock state.
- **Scroll Lock** — does not exist on Apple keyboards; macOS has no scroll-lock state.

The §6.0 spike tests all three anyway (they cost nothing to check, and one of them might work if you
ever plug a PC keyboard into the Mac), but **plan on Caps Lock.**

### The trigger: Caps Lock, tapped twice

This meets your "must not hamper other features" requirement better than it first sounds:

- A **single** Caps Lock press is ignored entirely → normal Caps Lock use is completely unaffected.
- A **double-tap nets to zero** — Caps Lock ends in exactly the state it started in. Nothing on the
  Mac is left changed.
- WireKey never sends Caps Lock itself (§1.5), so there is no feedback loop.
- There is **one** real interference risk — wrong-case characters during the tap window — and §3.5
  solves it completely. That section is the most important part of this plan.

### If the spike fails, this feature is not possible under your constraints

I have to be blunt: whether macOS actually forwards LED reports over Bluetooth HID, and whether
Android's `BluetoothHidDevice` surfaces them, is **empirical and unverified**. It is the only
zero-install channel that exists. If §6.0 shows no reports arrive, your remaining options are all
things you have ruled out — a small Mac helper over BLE, or Bluetooth PAN plus an Automator action.
**Run the spike first. It is 30 minutes and it is go/no-go for everything below.**

### Channels I checked and rejected

| Channel | Why not |
|---|---|
| **AVRCP media keys** (F8 *is* ⏯ on a Mac, and Macs route media keys to the audio device) | Receiving them needs A2DP-sink / AVRCP-target roles. Android does not expose these to third-party apps — `BluetoothProfile.A2DP_SINK` is disabled in AOSP and gated behind system permissions. |
| **Vendor-defined HID output report** | macOS seizes keyboard HID devices exclusively, so no third-party process can write to them. Also changes the descriptor ⇒ forces re-pairing. |
| **RFCOMM / SPP, or BLE GATT** | Both work, both need a helper running on the Mac. Ruled out. |
| **Bluetooth PAN + Automator** | Networking over Bluetooth rather than Wi-Fi, so it technically fits "Bluetooth only" — but still needs an Automator action and a `curl`, and reroutes the Mac's networking. Fallback only if §6.0 fails. |
| **Connect/disconnect the keyboard in the Mac's Bluetooth menu** | Is a genuine zero-software signal, but takes seconds and tears down the typing link. Unusable. |

### One thing worth exploring in built-in System Settings

If you want a key other than Caps Lock: **System Settings → Keyboard → Modifier Keys** can reassign
modifier keys, and *may* let you route another modifier to Caps Lock. I am flagging this as
**"test during the spike, do not assume"** — the panel does not distinguish left from right, so
mapping e.g. Option → Caps Lock would consume *both* Option keys, which would hamper other features
and defeat the purpose. Caps Lock remains the recommendation.

---

## 1. What I verified in the codebase

### 1.1 The hook points already exist and are empty

[HidKeyboardController.kt:139-141](app/src/main/java/com/wirekey/bluetooth/HidKeyboardController.kt#L139-L141)
and [HidKeyboardController.kt:147-149](app/src/main/java/com/wirekey/bluetooth/HidKeyboardController.kt#L147-L149)
are pure logging today. This feature fills them in — nothing existing is displaced.

### 1.2 The descriptor already declares an LED output report → **no re-pairing**

Decoding `hidKeyboardDescriptor()` at
[HidKeyboardController.kt:186-192](app/src/main/java/com/wirekey/bluetooth/HidKeyboardController.kt#L186-L192):

```
95 05 75 01   Report Count 5, Report Size 1
05 08         Usage Page (LEDs)
19 01 29 05   Usage Min 1 (NumLock) .. Usage Max 5 (Kana)
91 02         OUTPUT (Data,Var,Abs)     ← the host→device LED report, ALREADY PRESENT
95 01 75 03
91 01         OUTPUT (Constant)          ← 3 bits of padding
```

The host→device LED report is already advertised to the host. This feature needs **zero descriptor
bytes changed**, so the `CLAUDE.md` warning ("host OS caches the old descriptor per-device; you must
Remove Device and re-pair") **does not apply**. This is what makes the whole approach cheap and safe.

Bit layout of the output byte: `bit0 = NumLock (0x01)`, `bit1 = CapsLock (0x02)`,
`bit2 = ScrollLock (0x04)`, `bit3 = Compose`, `bit4 = Kana`.

### 1.3 No new Bluetooth permissions, no new services

[AndroidManifest.xml:11-15](app/src/main/AndroidManifest.xml#L11-L15) already declares everything
needed. The listener lives on the always-registered app-scoped `HidKeyboardController`, so
**[ConnectionKeepAliveService.kt](app/src/main/java/com/wirekey/bluetooth/ConnectionKeepAliveService.kt)
and [KeystrokeService.kt](app/src/main/java/com/wirekey/service/KeystrokeService.kt) need no changes
at all.** The only new permission in this entire plan is `VIBRATE` (install-time, no runtime prompt).

### 1.4 Pause/Resume are app-scoped; Start is not

[TypingSessionManager.kt](app/src/main/java/com/wirekey/service/TypingSessionManager.kt) is an
application singleton exposing `isSending`, `isPaused`, `pauseSend()`, `resumeSend()` — callable
from anywhere, any time.

**Start is different.** It needs the text, and the text lives in
[ComposeModeViewModel.kt:399-405](app/src/main/java/com/wirekey/ui/composemode/ComposeModeViewModel.kt#L399-L405)
— `getTextToSend()`, which resolves the A/B partial-execution markers against `draftText`. Per your
decision, Start stays scoped to the Compose screen and is delegated to a handler that
`ComposeModeViewModel` registers (§3.2).

### 1.5 WireKey never sends Caps Lock → no self-trigger loop

`KEY_CAPSLOCK = 0x39` is declared in
[HidKeyCodes.kt:21](app/src/main/java/com/wirekey/util/HidKeyCodes.kt#L21) but has **zero usages**
in `app/src/` — the app types capitals with Shift. So an incoming Caps Lock LED report can never be
an echo of something WireKey itself sent.

### 1.6 Everything else untouched

No change to `MarkovTyper`, `LanguageModel`, `KeyboardLayout`, `HumanTypingConfig`,
`TypingCadenceEngine`, `HidReportBuilder`, `AcousticTypingEngine`, `KeystrokeService`,
`ConnectionKeepAliveService`, `LiveModeViewModel`, `PairingViewModel`, or the send loop inside
`TypingSessionManager`.

---

## 2. Architecture

```
  Mac: Caps Lock tapped twice
        │
        ▼  HID LED output report, over the existing BR/EDR HID link
  HidKeyboardController.onSetReport  /  .onInterruptData     [currently empty — filled in]
        │  hostOutputReportListener(ByteArray)
        ▼
  LockKeyGestureDetector          pure, injected clock, fully unit-tested
        │  • dedupe identical reports
        │  • edge 1 while sending  ──▶ PROVISIONAL PAUSE (§3.5, the case-corruption fix)
        │  • edge 2 within window  ──▶ TRIGGER
        ▼
  RemoteControlCoordinator
        │  • 300 ms monotonic debounce
        │  • marshal onto Dispatchers.Main.immediate  (serializes all commands)
        │  • RemoteCommandResolver  (pure state machine)
        │  • haptic + notification feedback
        │
        ├── START ──────▶ ComposeModeViewModel.startFromRemote()   (registered handler)
        └── PAUSE/RESUME ▶ WireKeyApp.typingSessionManager.pauseSend() / .resumeSend()
```

### New files

| File | Purpose | Android deps? |
|---|---|---|
| `remote/RemoteCommand.kt` | `RemoteAction`, `LockKey`, `TriggerGesture` enums | none |
| `remote/RemoteCommandResolver.kt` | **Pure** Start/Pause/Resume state machine | none |
| `remote/LockKeyGestureDetector.kt` | **Pure** LED byte → gesture, injected clock | none |
| `remote/RemoteControlSettings.kt` | **Pure** immutable config snapshot (mirrors `CadenceSettings`) | none |
| `remote/RemoteControlCoordinator.kt` | Debounce, threading, handler registry, feedback | yes |

**Four of the five new files are pure Kotlin with no Android imports**, so essentially all the logic
is covered by fast JVM unit tests in `app/src/test/`, matching the existing `TypingCadenceEngineTest`
/ `HidReportBuilderTest` pattern. The only Android-touching file is the thin coordinator.

### Existing files touched — six, all strictly additive

| File | Change |
|---|---|
| `AndroidManifest.xml` | +1 permission: `VIBRATE`. No new components. |
| `WireKeyApp.kt` | +1 singleton `remoteControlCoordinator`; wire `hostOutputReportListener` once |
| `HidKeyboardController.kt` | Fill in the two empty callback bodies; +1 nullable listener field |
| `SettingsRepository.kt` | +5 preference keys, flows, setters |
| `SettingsScreen.kt` | +1 "Mac Remote Control" section |
| `ComposeModeViewModel.kt` | +`startFromRemote()`, register/unregister in `init`/`onCleared` |

---

## 3. Detailed design

### 3.1 The state machine (pure, `RemoteCommandResolver.kt`)

```kotlin
enum class RemoteAction { START, PAUSE, RESUME, IGNORED_NOTHING_TO_SEND, IGNORED_NO_SCREEN }

object RemoteCommandResolver {
    fun resolve(isSending: Boolean, isPaused: Boolean, hasStartHandler: Boolean): RemoteAction =
        when {
            isSending && isPaused -> RemoteAction.RESUME
            isSending             -> RemoteAction.PAUSE
            !hasStartHandler      -> RemoteAction.IGNORED_NO_SCREEN
            else                  -> RemoteAction.START
        }
}
```

Pause/Resume are checked **before** Start, so a running send stays controllable even if the Compose
screen was destroyed (e.g. you started a long send, then navigated to Live Mode).

### 3.2 Handler registration without leaks (`RemoteControlCoordinator.kt`)

The hazard: ViewModel A is destroyed *after* ViewModel B registers (Compose recreation ordering is
not guaranteed), and A's `onCleared` wipes B's handler. Fixed with identity-checked unregistration:

```kotlin
class StartHandlerRegistration internal constructor(
    private val coordinator: RemoteControlCoordinator,
    internal val handler: () -> Boolean        // true if a send actually started
) { fun unregister() = coordinator.unregister(this) }

private val startHandler = AtomicReference<StartHandlerRegistration?>()

fun registerStartHandler(h: () -> Boolean) =
    StartHandlerRegistration(this, h).also { startHandler.set(it) }

internal fun unregister(reg: StartHandlerRegistration) {
    startHandler.compareAndSet(reg, null)      // only clears if *this* registration is still current
}
```

In `ComposeModeViewModel`:

```kotlin
private val startRegistration = WireKeyApp.remoteControlCoordinator.registerStartHandler {
    val text = getTextToSend()
    if (text.isEmpty() || isSending.value) false else { sendToPc(); true }
}
override fun onCleared() { startRegistration.unregister(); super.onCleared() }
```

`sendToPc()` is reused verbatim, so partial-execution markers, `_lastSentText`, history recording
and the sent-text trail all work through the remote path with no duplicated logic.

**Lifetime.** With Navigation Compose the `compose_mode` back-stack entry survives navigating to
`settings` and survives backgrounding, so remote Start works in both — which covers the real use
case (phone in your pocket, you at the Mac). It is unavailable on `pairing` / `live_mode`, where it
returns `IGNORED_NO_SCREEN` and buzzes the rejection pattern rather than failing silently.

### 3.3 Threading and debouncing

LED reports arrive on the `Executors.newSingleThreadExecutor()` handed to `registerApp`
([HidKeyboardController.kt:47](app/src/main/java/com/wirekey/bluetooth/HidKeyboardController.kt#L47))
— a foreign thread.

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private var lastCommandAt = 0L                      // only touched on the main dispatcher

fun submit(action: RemoteAction) = scope.launch {    // every command serialized on main
    val now = SystemClock.elapsedRealtime()          // monotonic; immune to wall-clock changes
    if (now - lastCommandAt < MIN_COMMAND_INTERVAL_MS) return@launch
    lastCommandAt = now
    execute(action)
}
```

- `MIN_COMMAND_INTERVAL_MS = 300` absorbs a triple-tap and any duplicate delivery.
- Marshalling onto `Dispatchers.Main.immediate` serializes commands and matches how the UI buttons
  already call these methods — no new concurrency surface on `TypingSessionManager`.
- Read-then-act happens in one uninterrupted main-thread block, so there is no race.
  `sendToPc()` sets `_isSending.value = true` **synchronously** before its own `scope.launch`
  ([TypingSessionManager.kt:110](app/src/main/java/com/wirekey/service/TypingSessionManager.kt#L110)),
  so a command 301 ms later already observes the new state.
- Rapid Start→Cancel→Start is already safe via the existing `sendGeneration` +
  `oldJob?.cancelAndJoin()` guard — no change needed there.

### 3.4 Reading the LED byte, defensively

`HidKeyboardController` gains one nullable field and forwards from **both** callbacks, because host
stacks differ (control-channel `SET_REPORT` vs interrupt-channel `DATA`):

```kotlin
var hostOutputReportListener: ((ByteArray) -> Unit)? = null   // set once by WireKeyApp

override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
    Log.d(tag, "onSetReport: type=$type id=$id data=${data?.toHexString()}")
    if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) data?.let { hostOutputReportListener?.invoke(it) }
}
override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
    Log.d(tag, "onInterruptData: reportId=$reportId data=${data?.toHexString()}")
    data?.let { hostOutputReportListener?.invoke(it) }
}
```

The detector dedupes identical states arriving within 50 ms, so a host that delivers on both
channels does not double-count.

The descriptor declares no report IDs, so the payload should be exactly one byte — but handle the
plausible variants rather than assuming:

```kotlin
val ledByte = when {
    data.size == 1 -> data[0]
    data.size >= 2 -> data[1]      // some stacks prefix a report-ID byte
    else -> return                  // empty payload: ignore, never throw
}
val isOn = (ledByte.toInt() and triggerKey.mask) != 0    // mask = 0x02 for Caps Lock
```

### 3.5 The case-corruption problem — the most important section here

**This is the one way the trigger could hamper another feature, and it must be handled.**

Between your first and second Caps Lock tap, Caps Lock is genuinely **ON on the Mac**. If you are
double-tapping in order to **pause** a send, WireKey is still typing during that window — and the
host applies Caps Lock to those characters. At 150 WPM that is several characters silently
corrupted into the wrong case. macOS's own Caps Lock activation delay makes the window *longer*,
not shorter (§3.6), so this is not a theoretical concern.

**Fix — provisional pause on the first edge:**

1. First Caps Lock change arrives **while sending** → **pause immediately**, before the gesture is
   even resolved. Zero characters are typed while Caps Lock state is in flux.
2. Second change arrives within the window → it was your double-tap → the command was PAUSE anyway
   → **stay paused.** Correct outcome, nothing corrupted, and it feels *faster* than the gesture
   window because the pause already happened on tap one.
3. No second change arrives → it was a genuine Caps Lock press, and Caps Lock is now ON. Resuming
   would corrupt everything that follows, so **stay paused** and raise a notification:
   *"Paused — Caps Lock is on. Turn it off, then resume."* Strictly better than silently typing the
   rest of the message in the wrong case.

When **idle or already paused**, no provisional pause is needed — nothing is being typed, and by the
time the trigger fires on edge two, Caps Lock is already back OFF, so START and RESUME both begin
typing with correct case.

If §6.0 finds that macOS also propagates **Num Lock** or **Scroll Lock**, the trigger moves to one
of those and this entire section becomes unnecessary — neither affects character case. That is why
the spike tests all three.

### 3.6 Gesture detection (pure, `LockKeyGestureDetector.kt`, injected clock)

- Track `lastState` and `lastEdgeAt`. An **edge** is a report where the masked bit differs from
  `lastState`. Identical repeats are ignored — macOS re-sends state on reconnect and focus changes.
- **Two edges within `GESTURE_WINDOW_MS`** fire the command.
- A **single** edge fires nothing → ordinary Caps Lock use is unaffected.
- After firing, `LOCKOUT_MS = 700` swallows further edges so a triple-tap fires exactly once.

**On the window length.** macOS applies a deliberate activation delay to Caps Lock (you must hold it
briefly) specifically to prevent accidental toggling. This means a "quick double-tap" may take
**600-900 ms end to end**, not the 200-300 ms a normal double-click takes. So:

- `GESTURE_WINDOW_MS` defaults to **1200 ms**, deliberately generous.
- **The spike measures the real inter-report timing** and the constant is tuned to that measurement
  before the detector is finalised. This is an explicit deliverable of §6.0, not a guess left in
  the code.
- There is a residual risk that macOS's debouncing swallows the second tap entirely at high speed.
  The spike will show this. If it does, the fallback is single-tap mode (§3.7).

### 3.7 Optional later: single-tap mode

Offered as a **settings option, not the default, and not in the first working version.** For people
who never use Caps Lock and want a snappier trigger:

- One Caps Lock press fires the command immediately.
- The phone then **sends a Caps Lock keypress back** to restore the host's previous state —
  `KEY_CAPSLOCK = 0x39` already exists in `HidKeyCodes` and is currently unused (§1.5).
- Requires self-echo suppression: after sending our own Caps Lock, ignore the next inbound edge
  (with a timeout, so a dropped report cannot wedge the detector).
- Must serialize against the send loop's `controller.sendReport` calls — safe because the send is
  paused at that moment, but it needs to be explicit.

This is genuinely more complex (self-echo suppression, report interleaving) and it consumes the
Caps Lock key entirely, so it is deferred to §6.5 and only built if you want it after using
double-tap mode.

### 3.8 Feedback (you are looking at the Mac, not the phone)

Distinct haptics via `VibratorManager` (API 31+) / `Vibrator` (API 28-30), behind a settings toggle,
default **on**:

| Action | Pattern |
|---|---|
| START | one 60 ms buzz |
| PAUSE | two 40 ms buzzes |
| RESUME | one 120 ms buzz |
| Rejected (nothing to send / no Compose screen) | three 25 ms buzzes |

All vibrator calls wrapped in `try/catch` — some devices have no vibrator. The coordinator also
exposes a `StateFlow<RemoteNotice?>` that `ComposeModeScreen` renders as a Snackbar when in the
foreground, plus a notification (on a new low-importance channel; `POST_NOTIFICATIONS` is already in
the manifest) for the Caps-Lock-stuck-on warning from §3.5 step 3, since that one needs to reach you
while the phone is in your pocket.

### 3.9 Settings — all new keys, defaults preserve today's behaviour

Added to `SettingsRepository` in the existing style (`booleanPreferencesKey` + flow + suspend
setter). No existing key's default changes.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `remote_control_enabled` | Boolean | **false** | Master switch. Off ⇒ the listener returns immediately and nothing new runs. |
| `remote_trigger_key` | String | `"capslock"` | `capslock` / `numlock` / `scrolllock` — only the ones §6.0 validates are offered |
| `remote_trigger_gesture` | String | `"double"` | `double` / `single` (§3.7, later) |
| `remote_gesture_window_ms` | Int | `1200` | Tuned from the spike; exposed so you can adjust to your own tapping speed |
| `remote_haptics_enabled` | Boolean | true | Vibration confirmation |

The coordinator collects these flows once into a cached `@Volatile RemoteControlSettings` snapshot,
so the hot path (an inbound LED report) does no suspending work.

New **"Mac Remote Control"** section in `SettingsScreen`, between *Acoustic Typing Feedback* and
*Device*, using the existing `ListItem` + `Switch` idiom: master switch, trigger-key dropdown,
gesture mode, a window-length slider, the haptics toggle, and a short expandable explanation of the
Caps Lock double-tap and the §3.5 safety behaviour.

---

## 4. Test plan

### JVM unit tests (`app/src/test/java/com/wirekey/remote/`) — fast, no device

**`RemoteCommandResolverTest`** — exhaustive over all 8 combinations of
`(isSending, isPaused, hasStartHandler)`, including the impossible `!isSending && isPaused`, which
must resolve cleanly rather than crash.

**`LockKeyGestureDetectorTest`** (injected `() -> Long` clock) — the bulk of the coverage:
- single edge → no fire
- two edges 400 ms apart → exactly one fire
- two edges 1500 ms apart → no fire (outside a 1200 ms window)
- three edges within 600 ms → exactly one fire (lockout holds)
- duplicate identical reports → no fire, and they do **not** reset the window
- identical report on both callbacks within 50 ms → counted once
- 2-byte payload with a report-ID prefix → parsed identically to 1-byte
- empty / oversized / null payload → ignored, no exception
- NumLock bit (`0x01`) flipping while trigger is CapsLock → no fire
- **§3.5 provisional pause:** edge 1 while sending → PAUSE emitted immediately; edge 2 in window →
  stays paused, no second command; window expires with the bit ON → stays paused + warning raised
- **§3.5 idle path:** edge 1 while idle → no provisional pause; edge 2 → START

**`RemoteControlSettingsTest`** — window-length coercion, trigger-key parsing, unknown-value
fallback to defaults.

### Manual QA checklist

1. Master switch off → toggle Caps Lock repeatedly; confirm zero behaviour change anywhere.
2. Trigger with an empty draft → rejected, no crash, rejection haptic.
3. Trigger with text → typing starts; again → pauses mid-send; again → resumes and the plan
   continues from the same index (verify against `sendProgress`).
4. Trigger spammed rapidly → exactly the debounced number of state changes, never a double-start.
5. **Case integrity:** send a long lowercase string, pause via double-tap mid-send, resume, and
   diff the host's text against the source — **zero wrong-case characters**. This is the §3.5
   regression test and the single most important manual check.
6. Genuine single Caps Lock press mid-send → provisional pause + warning notification, no wrong-case
   characters, and resume is blocked until Caps Lock is off.
7. Genuine single Caps Lock press while idle → nothing happens; Caps Lock behaves normally.
8. Type normally on the Mac with Caps Lock while the feature is enabled but WireKey is idle →
   completely unaffected.
9. Pause remotely, then **Cancel** on the phone → state clean, `KeystrokeService` stopped.
10. Start remotely, navigate to Settings → remote Pause still works (app-scoped path).
11. Start remotely, navigate to **Live Mode** → Pause still works; a further Start is rejected with
    the rejection haptic.
12. Phone screen off during a send → remote pause/resume still lands.
13. Bluetooth disconnect and reconnect → detector state resets cleanly, no stale edge counted.
14. Partial-execution markers A/B set → remote Start sends exactly the marked range, and the
    sent-text trail shades the same range as a button-initiated send.
15. Regression sweep: Live Mode typing, acoustic feedback, history restore/resend, WPM slider, theme
    switching, Restart button — all unchanged.

---

## 5. Risks and mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| **macOS never forwards LED reports over Bluetooth HID** | **Blocking** | §6.0 spike first, before any real code. If it fails the feature is impossible under your constraints — see §0. |
| Wrong-case characters during the tap window | **High** | §3.5 provisional pause; QA step 5 is the explicit regression test; moot if the spike finds a non-case LED bit |
| macOS Caps Lock activation delay makes double-tap slow or unreliable | Medium | Generous 1200 ms default window, **tuned from real measurements in the spike**, user-adjustable; single-tap mode (§3.7) as the fallback |
| Accidental Caps Lock double-tap starts an unwanted send | Low | Master switch off by default; only fires while HID is connected; START additionally requires a non-empty draft and the Compose screen |
| Handler leak / stale ViewModel clobbering a newer one | Medium | Identity-checked `compareAndSet` unregistration (§3.2) |
| Duplicate delivery on both HID callbacks → double command | Medium | 50 ms cross-channel dedupe in the detector + 300 ms coordinator debounce |
| Detector wedged by a dropped report | Low | State is derived from the reported bit, not accumulated; a disconnect resets it (QA 13) |
| Regression in the existing send path | Low | Zero edits inside the send loop; `sendToPc()` reused verbatim; feature off by default |

---

## 6. Implementation order

### 6.0 Spike — go/no-go, do this before anything else (~30 min, throwaway code)

Add temporary hex logging to `onSetReport` / `onInterruptData`, pair with the Mac, then toggle
**Caps Lock**, **Num Lock**, and **Scroll Lock** several times each, plus a few deliberate
double-taps at different speeds. Read `logcat`.

**It answers five questions at once:**
1. Do LED reports arrive at all? *(If no → stop; the feature is impossible, see §0.)*
2. Which callback delivers them — `onSetReport`, `onInterruptData`, or both?
3. What is the exact payload shape (length, report-ID prefix)?
4. Is a **non-case** bit (Num/Scroll Lock) available? *(If yes → §3.5 can be deleted entirely.)*
5. **What is the real inter-report timing of a double-tap?** → sets `GESTURE_WINDOW_MS`.

Record all five answers at the top of this file before proceeding.

### 6.1 Core pipeline
`RemoteCommand.kt`, `RemoteCommandResolver.kt`, `RemoteControlSettings.kt`,
`RemoteControlCoordinator.kt`; `WireKeyApp` singleton; `ComposeModeViewModel` register/unregister +
`startFromRemote()`; `RemoteCommandResolverTest`. Verifiable in isolation via a temporary debug
button that calls `coordinator.submit()` — no Mac needed yet.

### 6.2 Settings persistence + UI
`SettingsRepository` keys/flows/setters; `SettingsViewModel` wiring; the "Mac Remote Control"
section in `SettingsScreen`.

### 6.3 Gesture detector
`LockKeyGestureDetector.kt` + its full test suite (pure, fast), written against the timings measured
in §6.0. Tests pass before any hardware is involved.

### 6.4 Wire it up + the §3.5 safety behaviour
`HidKeyboardController` listener field and the two callback bodies; provisional pause; haptics and
the Caps-Lock-on notification. Then the full §4 manual checklist, with QA step 5 as the gate.

### 6.5 Optional, only if you want it after living with double-tap
Single-tap mode (§3.7) with self-echo suppression.

---

## 7. Rollback

The master switch defaults to `false`, so the shipped-but-disabled state is behaviourally identical
to today. A full revert is: delete `app/src/main/java/com/wirekey/remote/` and revert the six
additive edits in §2 — no schema migration, no descriptor change, no re-pairing, nothing persisted
that other code reads.

---

## 8. Status — implemented

Built, compiling, and green: **70 unit tests pass** (43 new for this feature, 27 pre-existing and
unaffected), and `assembleDebug` produces an APK.

- ✅ §6.1 core pipeline, §6.2 settings + UI, §6.3 gesture detector, §6.4 wiring and the §3.5 safety
  behaviour.
- ➕ Added beyond the plan: an on-device **diagnostics panel** in Settings, so the §6.0 spike can be
  run from the phone instead of `adb logcat`. It stays live even while the feature is switched off,
  because "does my Mac send LED reports at all?" has to be answerable *before* deciding to turn the
  feature on.
- ⏸️ §6.5 single-tap mode is **deliberately not built**, exactly as the plan scoped it. It would
  consume the Caps Lock key entirely — which is the one thing you asked the trigger not to do — and
  needs self-echo suppression plus interleaving against the send loop's own HID reports.
- ⏳ **Still unverified on hardware:** whether macOS forwards LED reports at all. That is now a
  five-minute check in Settings rather than a code spike. If nothing appears in the diagnostics
  panel, the channel does not exist on your Mac and no configuration will change that.

### Two fixes found in self-review, after the code was first written

1. **A half-gesture could span an enable flip.** Reports are fed to the detector even while the
   feature is off (so the LED state stays current and enabling works immediately). That left a
   banked first edge behind: one tap with the feature off plus one tap after switching it on would
   complete a gesture neither tap intended. Fixed with `clearPendingGesture()`, called on every
   enable/disable flip — it drops the pending half while keeping the tracked state, so the next
   double tap still works in full. Covered by four new tests.
2. **The provisional pause could be released after the send had already ended.** Now guarded on
   `isSending`, so it can never un-pause something it did not pause.

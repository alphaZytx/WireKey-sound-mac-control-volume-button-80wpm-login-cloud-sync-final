# WireKey (AutoTyper)

WireKey (hosted as AutoTyper) is an Android application that turns your smartphone into a fully functional Bluetooth HID keyboard. It operates seamlessly without requiring any custom client software on the host computer.

## Features
- **Plug and Play**: No host software needed. Works out of the box with standard Bluetooth.
- **Cross-Platform**: Supports Windows, macOS, Linux, iPadOS, and ChromeOS.
- **Low Latency**: Utilizes Android's native `BluetoothHidDevice` API for immediate typing response.
- **Realistic Human Typing Engine**: An advanced Markov-chain typing simulator designed to bypass sophisticated keystroke analysis and anti-cheat systems.
- **Two Typing Modes**: Live Mode types as you type; Compose Mode plays back a whole block of text.
- **Partial Text Execution**: Drop A/B markers to send only a chosen slice of the draft.
- **Live Execution Tracker**: Watch the exact character being typed, with a trail of what has already been sent.
- **Real Keystroke Audio**: Plays real recorded MacBook key clicks from the phone, in sync with every keystroke sent.
- **Mac Remote Control**: Start, pause and resume a send by double-tapping Caps Lock on the Mac.
- **Cloud Sync (Supabase)**: Sign in and keep one Compose Mode document identical on every phone using the account, updated live.
- **Volume-Key Speed Control**: Nudge the typing speed up or down with the phone's volume buttons, mid-send.
- **Resilient Connection**: Automatic reconnection with backoff, plus foreground services that keep the link and the send alive.

## Typing Modes

### Live Mode
Types on the host in real time as you type on the phone.

- **Sticky modifiers**: Ctrl, Alt and Win latch for the next keystroke, so combinations like Ctrl+C work one-handed.
- **Quick-access keys**: Esc, Tab, Enter, Backspace and arrow keys.
- **Local edit sync**: Editing text you already sent moves the host's cursor to match, rather than retyping the line.

### Compose Mode
Type or paste a block of text and play it back through the Human Typing Engine.

- **Live speed control**: A 10–250 WPM slider retimes the send while it is running.
- **Volume-key speed control**: Volume Up / Down adjust the speed by 5 WPM per press, clamped to the slider's range. Holding a key ramps. Active only while Compose Mode is on screen and the app is open; everywhere else the volume keys behave normally.
- **Pause, Resume, Restart and Cancel** at any point during a send.
- **Progress and time remaining**, updated continuously.
- **Live Execution Tracker**: Highlights the character currently being typed and reports its line and column.
- **Sent Text Trail**: Shades the text already delivered, so a long send is easy to follow.
- **Partial Text Execution**: Tap to place a Start (A) and/or End (B) marker and send only that region — A→B, A→End or Start→B.
- **Send History**: Recent messages are stored and can be re-sent.
- **Paste from clipboard** in one tap, and **Clear All** to empty the draft.

## Human Typing Engine
WireKey's core advantage is its deeply realistic **Typing Cadence Engine**. Instead of typing text instantly or using a static, uniform delay, it utilizes a full **Markov-chain simulation** that models human physiological and cognitive behavior. 

**Techniques & Features include:**
- **Variable Per-Keystroke Timing**: Keystroke delays are calculated dynamically using Gaussian distributions. Factors include keyboard geometry (travel distance between keys), bigram frequencies, word difficulty, and shift-key reach times.
- **Dynamic Errors & Corrections**: The engine probabilistically generates realistic mistakes, such as hitting neighboring keys, swapping characters (anticipation errors), and then detects them after a "cognitive reaction time" to manually backspace and fix the error.
- **Cognitive Muscle-Memory Typos**: It models programming-specific typos, such as typing `i++` instead of `i--`, or `length()` instead of `length`, and eventually corrects them.
- **Shift-Key Synchronization Errors**: Simulates the human tendency to release the shift key too late during CamelCase or PascalCase typing (e.g., typing `REsult` instead of `Result`).
- **Syntax Deceleration**: Natively slows down typing speed for awkward programming symbols and dense syntax chunks (like `->`, `::`, or `!=`).
- **Fatigue Accumulation**: A multiplier gradually increases typing delays over long texts to simulate hand fatigue.
- **IDE Auto-Indent Syncing**: Smartly predicts IDE behavior after a carriage return, adjusting virtual cursors and trimming redundant spaces to prevent formatting corruption in code editors.

## Real Keystroke Audio
The phone plays real recorded MacBook key clicks in time with the keystrokes it sends, so a send sounds like someone typing rather than a silent machine.

- Samples are sliced **per physical key**, with separate press and release sounds and several variants per key to avoid an obviously repeating loop.
- Two bundled profiles: **Real Sound Mode** (a phone mic beside the MacBook) and **Zoom Recording** (the same typing captured through Zoom, with its own mic colour and compression).
- Playback is fire-and-forget on its own coroutine scope, so audio never delays the keystroke loop.
- Toggle and profile picker live in **Settings → Keyboard Click Sounds / Keyboard Sound Profile**.

The packs are generated by `scripts/generate_macbook_pack.py` from a third-party recording dataset. That dataset is not committed (it is ~314 MB); only the derived packs the app loads at runtime ship in the repo.

## Mac Remote Control
A macOS menu-bar helper (`mac-remote-helper/`) drives Compose Mode from the Mac itself: double-tap Caps Lock to Start, then again to Pause and Resume, without touching the phone.

The Mac and the phone talk over a private **Bluetooth LE** service, separate from the HID keyboard link. The helper only listens for Caps Lock — it never suppresses the key or changes its normal behaviour — and it sends a single fixed two-byte command. No typed text travels on that channel.

```zsh
zsh scripts/install-mac-remote-helper.sh     # build, install to /Applications, launch
zsh scripts/uninstall-mac-remote-helper.sh   # remove app, settings and privacy approvals
zsh scripts/package-mac-remote-helper.sh     # build a drag-installable .dmg
```

Then turn on **Settings → Mac Remote Control** in the Android app. The helper needs Bluetooth and Input Monitoring permission on the Mac; its `WK` menu reports the two halves separately, so you can tell a dead key listener from a dropped Bluetooth link. See [mac-remote-helper/README.md](mac-remote-helper/README.md) for details.

**Also supported, without the helper:** the phone can read the host's keyboard **LED reports** directly over Bluetooth HID, so a double-toggle of **Caps Lock, Num Lock or Scroll Lock** on any host works as the same trigger. This depends on the host mirroring its lock-key LEDs to the Bluetooth keyboard, which macOS frequently does not — hence the helper.

- **Selectable trigger key**: Caps Lock (default), Num Lock or Scroll Lock.
- **Adjustable gesture window**: 300–3000 ms, default 1200 ms, because macOS's deliberate Caps Lock activation delay makes a "quick" double-tap slower than a normal double-click.
- **Haptic confirmation**: distinct vibration patterns for Start, Pause, Resume and rejected, so you know what happened without looking at the phone.
- **Diagnostics panel**: a live log of inbound reports and accepted triggers, with copy and clear.

## Cloud Sync (Supabase)

Optional, and entirely separate from Bluetooth: sync never sends a keystroke, and the HID path
never touches the network. A build with no Supabase project configured hides the feature
completely and behaves exactly as before.

**What it does**
- **Email/password accounts**, with the session persisted so the phone stays signed in across launches.
- **One Compose document per account.** The **Sync** button in Compose Mode replaces the cloud copy with the complete current editor contents.
- **Last Sync wins.** No merging, no conflict dialogs, no version prompts.
- **Live fan-out.** When one phone syncs, every other phone on the account replaces its editor with the new text immediately, over Supabase Realtime.
- **On sign-in and on launch**, the cloud copy is fetched and replaces the local editor. The first sign-in creates an empty document.
- **Status** is shown in Compose Mode as *Synced* / *Syncing…* / *Sync failed*, alongside **Sign in** / **Account**.

The A/B markers are not involved: they choose what gets *typed over Bluetooth*, while Sync always
pushes the whole document. A send already in flight is never interrupted by an incoming sync —
the typing session works from its own snapshot — but the editor underneath it is replaced.

### Setting it up

1. Create a project at [supabase.com](https://supabase.com).
2. **SQL Editor → New query**, paste [`supabase/compose_sync_schema.sql`](supabase/compose_sync_schema.sql) and run it. That creates `public.compose_documents`, its Row Level Security policies, and adds the table to the realtime publication.
3. **Authentication → Sign In / Providers**: make sure **Email** is enabled. For testing, turn **Confirm email** off, or confirm each new account from the emailed link — until an address is confirmed, sign-in is refused.
4. **Project Settings → API**: copy the **Project URL** and the **anon / publishable** key.
5. Put them in `local.properties` (gitignored, never committed):

   ```properties
   supabase.url=https://<project-ref>.supabase.co
   supabase.anonKey=<anon / publishable key>
   ```

   `SUPABASE_URL` / `SUPABASE_ANON_KEY` environment variables override these, for CI.
6. Rebuild. **Settings → Cloud Sync**, or the **Sign in** button in Compose Mode, opens the account screen.

To test the fan-out, sign both phones into the same account, type on one and press **Sync**.

### Security

- Only the **anon / publishable** key is compiled into the app. It is designed to be public, and every request it makes is still checked against Row Level Security. **Never put a service-role or secret key in the app** — it bypasses RLS entirely.
- Rows are owned by `auth.users.id`, not by email address, and all four policies compare against `auth.uid()`. A signed-in user can reach exactly one row: their own.
- Realtime messages are filtered by the same policies, so no phone is ever notified about another account's document.

## Settings
- **Human Typing Simulation** — full Markov simulation, or a simple uniform delay.
- **Natural typing pacing** — contextual pauses at word and sentence boundaries.
- **Code Editor Mode** — IDE-aware handling of auto-indent and bracket auto-close.
- **Typing speed** — 20–150 WPM, default 80.
- **Theme** — System, Light or Dark.
- **Keyboard Click Sounds** and **Keyboard Sound Profile**.
- **Mac Remote Control** — on/off, live Bluetooth LE status, trigger key, gesture window, and **Vibrate on remote command**.
- **Cloud Sync** — account status, and the way in to sign in, register or sign out. Hidden when no Supabase project is configured.
- **Manage paired devices** — jumps to Android's Bluetooth settings to unpair.
- **Diagnostics** — remote-control event log.

## Building

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # instrumented tests (needs a device/emulator)
```

The Android app also opens directly in Android Studio. The macOS helper needs a Swift toolchain (Xcode command line tools) and is built by the scripts above.

If `./gradlew` reports "Unable to locate a Java Runtime", point it at the JDK bundled with Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

## Requirements
- **Minimum Android Version**: Android 9 (API 28). This is required because Android introduced native APIs for hosting `BluetoothHidDevice` profiles (the foundation of WireKey's functionality) in API 28.
- **Host Device**: Any computer or tablet that supports standard Bluetooth keyboard pairing.
- **For Mac Remote Control**: macOS 13 or later.

## Permissions Explained
WireKey requires several permissions to function securely and reliably:
- `BLUETOOTH_CONNECT`: Allows the app to connect with already-paired Bluetooth devices.
- `BLUETOOTH_SCAN`: Allows the app to look for nearby Bluetooth devices (without tracking location).
- `BLUETOOTH_ADVERTISE`: Allows the phone to broadcast itself as a discoverable keyboard so your PC can find it during the initial pairing process, and to advertise the Bluetooth LE control service the Mac helper connects to.
- `INTERNET` / `ACCESS_NETWORK_STATE`: Used only by Cloud Sync, to reach Supabase. Nothing in the Bluetooth or typing path uses the network.
- (Legacy) `ACCESS_FINE_LOCATION`: Required on Android 11 and below to perform Bluetooth discovery.

## How to Pair
1. Open the WireKey app and grant the requested permissions.
2. Ensure your phone's Bluetooth is turned on.
3. Tap **Make this phone discoverable** in WireKey. Your phone is now broadcasting its availability.
4. On your PC, open Bluetooth Settings and select **Add Device**.
5. Look for your phone's name (e.g., "Pixel 7 Pro") in the PC's Bluetooth list and pair it.
6. Once paired, the PC will appear in WireKey's bonded devices list.
7. Tap the device in WireKey to connect. You will automatically drop into Live Mode!

**Test Connection** on the pairing screen types `WireKey test 123` into whatever has focus on the host, so you can confirm the link works before relying on it.

## Reliability
- **Automatic reconnection**: a fixed backoff ladder (1s, 2s, 4s, 8s, 10s, 10s) retries a dropped link.
- **Connection keep-alive service**: a foreground service holds the Bluetooth connection open so the OS is less likely to kill it.
- **Keystroke service**: a second foreground service covers the lifetime of a send, so a long Compose Mode send survives the screen turning off.

## Known Limitations
- **Background Restrictions**: Some custom Android skins (OEM implementations) heavily restrict background Bluetooth connections. If the app goes to sleep, the HID connection may drop. WireKey includes auto-reconnection logic, but if the OS kills the process entirely, you will need to re-open the app.
- **Development Re-pairing**: If you are a developer modifying the HID descriptor in the source code, the host PC may cache the old descriptor. You must "Remove Device" on your PC and re-pair from scratch for the new layout to take effect.
- **Volume keys in Compose Mode**: while Compose Mode is on screen the volume buttons change typing speed, not the phone's volume. Use the system volume slider, or any other screen, to change volume.
- **Lock-key LED triggers**: macOS usually does not mirror Caps Lock's LED to a Bluetooth keyboard, so on a Mac use the `mac-remote-helper` app rather than the LED path.
- **Mac helper permissions**: the helper is ad-hoc signed, so every rebuild changes its code signature and macOS silently stops honouring the previous Input Monitoring approval. The install script clears the stale approval so it can be granted again.

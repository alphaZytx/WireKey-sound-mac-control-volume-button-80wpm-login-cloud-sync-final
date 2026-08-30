# WireKey Remote for macOS

This small menu-bar helper makes a physical Mac Caps Lock double-tap control the WireKey Android app over Bluetooth LE.

## Install and uninstall

From the repository root:

```zsh
zsh scripts/install-mac-remote-helper.sh     # build, install to /Applications, launch
zsh scripts/uninstall-mac-remote-helper.sh   # remove app, settings and privacy approvals
```

Or use `mac-remote-helper/dist/WireKey Remote.dmg`, which carries the app plus `Install.command`
and `Uninstall.command`. Rebuild that image with `zsh scripts/package-mac-remote-helper.sh`.

Prefer these over dragging the app to Applications. The helper is ad-hoc signed, so every build
has a different code-signing hash; macOS then keeps listing the previous Input Monitoring
approval as enabled while silently refusing to honour it, and the app cannot re-prompt on its
own. The install step clears that stale approval so the prompt comes back.

To build without installing:

```zsh
zsh scripts/build-mac-remote-helper.sh
open "mac-remote-helper/build/WireKey Remote.app"
```

On first launch, allow Bluetooth and Input Monitoring. If the latter prompt was dismissed, use the `WK` menu-bar icon and select **Retry Input Monitoring**, then allow WireKey Remote in **System Settings → Privacy & Security → Input Monitoring**.

## Use

1. In WireKey Android, open **Settings → Mac Remote Control** and turn it on.
2. Wait for the phone to show **Ready — waiting for WireKey Remote on the Mac**, then for the helper to show **Ready**.
3. Keep Compose Mode alive when starting a message. Press Caps Lock twice to Start; repeat to Pause, then Resume.

The helper is a passive event listener: it never suppresses Caps Lock or changes its normal behavior. It sends only the fixed two-byte trigger command over Bluetooth LE; it never reads or sends typed text.

The helper listens to Caps Lock's raw keyboard press rather than waiting for macOS to finish
activating the lock state. Double-press the key normally and promptly (within about three
quarters of a second). Keep the `WK` helper running; once it says **Ready**, commands are sent
over the already-open Bluetooth LE link with no scan or reconnect delay.

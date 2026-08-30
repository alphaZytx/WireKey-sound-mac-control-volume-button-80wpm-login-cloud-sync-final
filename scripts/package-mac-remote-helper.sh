#!/bin/zsh
# Builds mac-remote-helper/dist/WireKey Remote.dmg: the app plus double-clickable
# Install.command and Uninstall.command.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HELPER_DIR="$(cd "$SCRIPT_DIR/../mac-remote-helper" && pwd)"
BUILT_APP="$HELPER_DIR/build/WireKey Remote.app"
DIST_DIR="$HELPER_DIR/dist"
DMG="$DIST_DIR/WireKey Remote.dmg"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

zsh "$SCRIPT_DIR/build-mac-remote-helper.sh" >/dev/null
cp -R "$BUILT_APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"

cat > "$STAGE/Install.command" <<'EOF'
#!/bin/zsh
# Installs WireKey Remote from this disk image. Use this instead of dragging the app when you are
# replacing an existing copy: a new build has a new code-signing hash, and macOS keeps listing the
# old Input Monitoring approval while quietly refusing to honour it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
INSTALLED="/Applications/WireKey Remote.app"
BUNDLE_ID="com.wirekey.macremote"

print "Stopping any running copy…"
osascript -e "quit app id \"$BUNDLE_ID\"" 2>/dev/null || true
pkill -f "WireKey Remote.app/Contents/MacOS/WireKeyRemote" 2>/dev/null || true
sleep 1

print "Installing to $INSTALLED…"
rm -rf "$INSTALLED"
cp -R "$HERE/WireKey Remote.app" "$INSTALLED"
tccutil reset ListenEvent "$BUNDLE_ID" >/dev/null 2>&1 || true
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister \
    -f "$INSTALLED" 2>/dev/null || true
open "$INSTALLED"

cat <<'NOTE'

Installed. Open the WK menu-bar icon; it shows two lines:

    Phone:     Ready — press Caps Lock twice to control WireKey
    Caps Lock: Fast Caps Lock listener is ready

If the Caps Lock line asks for Input Monitoring, choose "Retry Input Monitoring" in that menu
and approve WireKey Remote in System Settings > Privacy & Security > Input Monitoring.

You can close this window.
NOTE
EOF

cat > "$STAGE/Uninstall.command" <<'EOF'
#!/bin/zsh
# Removes WireKey Remote, its saved settings and its privacy approvals. Dragging the app to the
# Trash leaves the last two behind, which is why this exists.
set -euo pipefail
INSTALLED="/Applications/WireKey Remote.app"
BUNDLE_ID="com.wirekey.macremote"

osascript -e "quit app id \"$BUNDLE_ID\"" 2>/dev/null || true
pkill -f "WireKey Remote.app/Contents/MacOS/WireKeyRemote" 2>/dev/null || true
sleep 1

if [[ -d "$INSTALLED" ]]; then rm -rf "$INSTALLED"; print "Removed $INSTALLED"; else print "Not installed."; fi
defaults delete "$BUNDLE_ID" >/dev/null 2>&1 && print "Removed saved settings" || true
tccutil reset ListenEvent "$BUNDLE_ID" >/dev/null 2>&1 && print "Cleared Input Monitoring approval" || true
tccutil reset Bluetooth "$BUNDLE_ID" >/dev/null 2>&1 && print "Cleared Bluetooth approval" || true

print "\nUninstalled. Turn off Settings > Mac Remote Control in the WireKey Android app"
print "to stop the phone advertising. You can close this window."
EOF

cat > "$STAGE/README.txt" <<'EOF'
WireKey Remote for macOS
========================

Double-tap Caps Lock on this Mac to Start / Pause / Resume typing on the WireKey Android app.

INSTALL      Double-click Install.command
UNINSTALL    Double-click Uninstall.command

Use Install.command rather than dragging the app to Applications. The helper is ad-hoc signed,
so each new build has a different code-signing hash; macOS then keeps showing the old Input
Monitoring approval as enabled while silently ignoring it. Install.command clears that stale
approval so the app can ask for permission again.

First launch needs two permissions:
  - Bluetooth        (granted by the prompt on first run)
  - Input Monitoring (System Settings > Privacy & Security > Input Monitoring)

The WK menu-bar icon reports both halves separately:
    Phone:     whether the Bluetooth LE link to the phone is up
    Caps Lock: whether the key listener is actually running

On the phone: Settings > Mac Remote Control must be ON.

The helper never suppresses or alters Caps Lock, and never reads or sends typed text. It sends
one fixed two-byte command over Bluetooth LE.
EOF

chmod +x "$STAGE/Install.command" "$STAGE/Uninstall.command"
mkdir -p "$DIST_DIR"
hdiutil create -volname "WireKey Remote" -srcfolder "$STAGE" -ov -format UDZO -quiet "$DMG"
print "Packaged: $DMG"

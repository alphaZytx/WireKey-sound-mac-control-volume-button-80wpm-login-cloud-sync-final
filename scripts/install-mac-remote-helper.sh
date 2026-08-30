#!/bin/zsh
# Installs WireKey Remote into /Applications and starts it.
#
# Re-run this any time you rebuild: the helper is ad-hoc signed, so every new binary gets a new
# code-signing hash and macOS silently stops honouring the old Input Monitoring grant while still
# listing the app as enabled. This script clears that stale grant so the app can ask again.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HELPER_DIR="$(cd "$SCRIPT_DIR/../mac-remote-helper" && pwd)"
BUILT_APP="$HELPER_DIR/build/WireKey Remote.app"
INSTALLED_APP="/Applications/WireKey Remote.app"
BUNDLE_ID="com.wirekey.macremote"

if [[ "${1:-}" == "--no-build" ]]; then
    [[ -d "$BUILT_APP" ]] || { print -u2 "No built app at $BUILT_APP. Run without --no-build."; exit 1; }
    print "Using existing build."
else
    print "Building…"
    zsh "$SCRIPT_DIR/build-mac-remote-helper.sh" >/dev/null
fi

print "Stopping any running copy…"
osascript -e "quit app id \"$BUNDLE_ID\"" 2>/dev/null || true
pkill -f "WireKey Remote.app/Contents/MacOS/WireKeyRemote" 2>/dev/null || true
sleep 1

print "Installing to $INSTALLED_APP…"
rm -rf "$INSTALLED_APP"
cp -R "$BUILT_APP" "$INSTALLED_APP"
codesign --verify --deep --strict "$INSTALLED_APP"

# The binary's signature changed, so the old Input Monitoring approval no longer matches it.
# Clearing it makes the grant "undetermined" again, which is what lets the app re-prompt.
print "Clearing the stale Input Monitoring approval…"
tccutil reset ListenEvent "$BUNDLE_ID" >/dev/null 2>&1 || true

/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister \
    -f "$INSTALLED_APP" 2>/dev/null || true

print "Starting…"
open "$INSTALLED_APP"

cat <<'NOTE'

Installed. Open the WK menu-bar icon and check both lines:

    Phone:     Ready — press Caps Lock twice to control WireKey
    Caps Lock: Fast Caps Lock listener is ready

If the Caps Lock line asks for Input Monitoring, choose "Retry Input Monitoring" in that
menu, then approve WireKey Remote in System Settings > Privacy & Security > Input Monitoring.
NOTE

#!/bin/zsh
# Removes WireKey Remote from this Mac: the app, its saved settings, and its privacy approvals.
#
# Nothing here touches the WireKey Android app or the project sources -- reinstall any time with
# install-mac-remote-helper.sh.
set -euo pipefail

INSTALLED_APP="/Applications/WireKey Remote.app"
BUNDLE_ID="com.wirekey.macremote"
removed_any=0

print "Stopping WireKey Remote…"
osascript -e "quit app id \"$BUNDLE_ID\"" 2>/dev/null || true
pkill -f "WireKey Remote.app/Contents/MacOS/WireKeyRemote" 2>/dev/null || true
sleep 1

if [[ -d "$INSTALLED_APP" ]]; then
    rm -rf "$INSTALLED_APP"
    print "Removed $INSTALLED_APP"
    removed_any=1
else
    print "Not installed at $INSTALLED_APP"
fi

# The remembered phone, so a reinstall starts by scanning rather than chasing a stale address.
if defaults read "$BUNDLE_ID" >/dev/null 2>&1; then
    defaults delete "$BUNDLE_ID" >/dev/null 2>&1 || true
    print "Removed saved settings ($BUNDLE_ID)"
    removed_any=1
fi

# Privacy approvals. These outlive the app bundle, so leaving them behind would keep a stale
# entry listed in System Settings for an app that is no longer here.
tccutil reset ListenEvent "$BUNDLE_ID" >/dev/null 2>&1 && print "Cleared Input Monitoring approval" || true
tccutil reset Bluetooth "$BUNDLE_ID" >/dev/null 2>&1 && print "Cleared Bluetooth approval" || true

if (( removed_any )); then
    print "\nUninstalled."
else
    print "\nNothing to remove."
fi
print "Turn off Settings > Mac Remote Control in the WireKey Android app to stop it advertising."

#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HELPER_DIR="$(cd "$SCRIPT_DIR/../mac-remote-helper" && pwd)"
APP_DIR="$HELPER_DIR/build/WireKey Remote.app"

cd "$HELPER_DIR"
swift build -c release

rm -rf "$APP_DIR"
mkdir -p "$APP_DIR/Contents/MacOS"
cp ".build/release/WireKeyRemote" "$APP_DIR/Contents/MacOS/WireKeyRemote"
cp "Info.plist" "$APP_DIR/Contents/Info.plist"
codesign --force --sign - "$APP_DIR"

echo "Built: $APP_DIR"
echo "Open it with: open \"$APP_DIR\""

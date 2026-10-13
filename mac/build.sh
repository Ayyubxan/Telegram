#!/bin/bash
# Nexo.app — Mac clipboard companion of the Telegram fork. `./build.sh --install` — /Applications ga o'rnatib ishga tushiradi.
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release
BIN="$(swift build -c release --show-bin-path)/Nexo"

APP="build/Nexo.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Nexo"
cp Resources/Info.plist "$APP/Contents/Info.plist"
[ -f Resources/AppIcon.icns ] && cp Resources/AppIcon.icns "$APP/Contents/Resources/"

# Ad-hoc imzo (Apple Developer akkaunti shart emas)
codesign --force --sign - --identifier uz.nexo.mac "$APP"
echo "✓ $APP"

if [[ "${1:-}" == "--install" ]]; then
    pkill -x Nexo 2>/dev/null || true
    pkill -x Nizam 2>/dev/null || true
    sleep 0.5
    rm -rf /Applications/Nexo.app /Applications/Nizam.app
    cp -R "$APP" /Applications/
    open /Applications/Nexo.app
    echo "✓ /Applications/Nexo.app ishga tushdi"
fi

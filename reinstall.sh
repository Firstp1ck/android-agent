#!/bin/bash
# Rebuild and reinstall Android Agent to Waydroid

set -e

cd "$(dirname "$0")"

echo "🔨 Building debug APK..."
./gradlew assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK" ]; then
    echo "❌ Build failed - APK not found"
    exit 1
fi

echo "✅ Build successful"

# Check Waydroid status
if waydroid status 2>/dev/null | grep -q "RUNNING"; then
    echo "📲 Installing to Waydroid..."
    waydroid app install "$APK"
    echo "✅ Installed! Launch 'AI Agent' from Waydroid."
else
    echo ""
    echo "⚠️  Waydroid not running. Start it with:"
    echo "    waydroid session start"
    echo ""
    echo "Then run:"
    echo "    waydroid app install $APK"
fi


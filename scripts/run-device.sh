#!/bin/bash
set -e

# Build, install, and launch the app on a connected Android device.

echo "==> Checking for connected device..."
DEVICE=$(adb devices -l | grep -v "List" | grep "device" | head -1 | awk '{print $1}')

if [ -z "$DEVICE" ]; then
    echo "ERROR: No Android device connected. Connect a device or start an emulator."
    exit 1
fi

echo "==> Found device: $DEVICE"

echo "==> Building debug APK..."
./gradlew assembleDebug --console=plain --quiet

echo "==> Installing APK..."
# Uninstall first to avoid version/downgrade conflicts
adb uninstall com.hriyaan.photostorage 2>/dev/null || true
adb install app/build/outputs/apk/debug/app-debug.apk

echo "==> Launching app..."
adb shell am start -n com.hriyaan.photostorage/.MainActivity

echo "==> Done. App is running on $DEVICE"

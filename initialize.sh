#!/usr/bin/env bash
# Initialize this Android/Gradle project: write local.properties and build.
set -euo pipefail
cd "$(dirname "$0")"

# Point Gradle at the local Android SDK (ANDROID_HOME, or the macOS default).
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [ ! -f local.properties ]; then
  echo "==> Writing local.properties (sdk.dir=$SDK_DIR)"
  echo "sdk.dir=$SDK_DIR" > local.properties
else
  echo "==> local.properties already exists, leaving it as is"
fi

echo "==> Building debug APK (downloads Gradle deps)"
./gradlew assembleDebug

echo "==> Done."

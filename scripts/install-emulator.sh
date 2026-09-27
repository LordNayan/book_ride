#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-/private/tmp/saathi-sdk}"
adb="$sdk_root/platform-tools/adb"

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi

if [[ ! -x "$adb" ]]; then
  echo "ADB missing at $adb. Set ANDROID_SDK_ROOT." >&2
  exit 1
fi

./gradlew testDebugUnitTest assembleDebug --console=plain
"$adb" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk

if [[ $# -gt 0 ]]; then
  "$adb" -s emulator-5554 shell am start -n com.screensaathi/.MainActivity \
    --es saathi_action preview --es destination "$*"
else
  "$adb" -s emulator-5554 shell am start -n com.screensaathi/.MainActivity
fi

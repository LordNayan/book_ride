#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-/private/tmp/saathi-sdk}"
avd_home="${ANDROID_AVD_HOME:-/private/tmp/saathi-avd}"

if [[ ! -x "$sdk_root/emulator/emulator" ]]; then
  echo "Android Emulator missing at $sdk_root. Set ANDROID_SDK_ROOT." >&2
  exit 1
fi

exec env ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root" \
  ANDROID_AVD_HOME="$avd_home" "$sdk_root/emulator/emulator" \
  -avd book_ride_api35 "$@"

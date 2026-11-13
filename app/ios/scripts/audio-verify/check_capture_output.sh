#!/usr/bin/env bash

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ "$#" -ne 2 ]; then
  echo "usage: $0 SOURCE REPORT.json" >&2
  exit 2
fi
WORK="$ROOT/native-core/target/audio-validation-work/capture-monitor"
mkdir -p "$WORK"
FW="$ROOT/../../core/apple/build/Frameworks/NativeCoreFFI.xcframework/macos-arm64"
xcrun swiftc -O -o "$WORK/capture-output" \
  -F "$FW" -framework NativeCoreFFI -framework CoreAudio \
  -framework AudioToolbox -framework CoreFoundation \
  "$ROOT/../../core/apple/src/Generated/NativeCore.swift" \
  "$ROOT/scripts/audio-verify/capture-output/main.swift"
exec "$WORK/capture-output" "$1" "$WORK/capture" "$2"

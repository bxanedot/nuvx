#!/bin/bash












set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

fw="$repo/../../core/apple/build/Frameworks/NativeCoreFFI.xcframework/macos-arm64"
if [ ! -d "$fw" ]; then
  echo "no NativeCoreFFI for macos-arm64 — run scripts/build-native-core.sh first" >&2
  exit 2
fi

bin="$(mktemp -d)/engine-verify"



xcrun swiftc -O -o "$bin" \
  -F "$fw" -framework NativeCoreFFI \
  -framework CoreAudio -framework AudioToolbox -framework CoreFoundation \
  -Xlinker -rpath -Xlinker "$fw/NativeCoreFFI.framework/Versions/A" \
  "$repo/../../core/apple/src/Generated/NativeCore.swift" \
  "$here/engine-verify/main.swift" || exit 2



exec "$bin"

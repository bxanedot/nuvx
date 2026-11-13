#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fw="$repo/../../core/apple/build/Frameworks/NativeCoreFFI.xcframework/macos-arm64"
xcrun swiftc -parse-as-library -O -module-cache-path "$work/modules" -o "$work/verify" \
 -F "$fw" -framework NativeCoreFFI -framework CoreAudio -framework AudioToolbox \
 "$repo/../../core/apple/src/Generated/NativeCore.swift" "$repo/../../core/apple/src/PlaybackSession/HeadphoneRouting.swift" \
 "$repo/../../core/apple/src/PlaybackSession/PlaybackDebugLog.swift" "$repo/scripts/headphones-verify/main.swift"
"$work/verify"

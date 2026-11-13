#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
fw="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
bin="$(mktemp -d)/playback-settings-verify"
xcrun swiftc -module-cache-path /tmp/nuvx-swift-module-cache -o "$bin" \
  -F "$fw" -framework NuvxShared \
  -Xlinker -rpath -Xlinker "$fw/NuvxShared.framework/Versions/A" \
  "$repo/scripts/settings-verify/main.swift"
for mode in missing off on; do "$bin" "$mode"; done

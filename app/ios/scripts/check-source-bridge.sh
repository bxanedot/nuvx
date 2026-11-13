#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
fw="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
xcrun swiftc -O -module-cache-path /tmp/nuvx-swift-module-cache -o "$scratch/verify" \
  -F "$fw" -framework NuvxShared \
  -Xlinker -rpath -Xlinker "$fw/NuvxShared.framework/Versions/A" \
  "$repo/../../core/apple/src/PlaybackSession/SourceSubstitution.swift" "$repo/scripts/source-bridge-verify/main.swift"
"$scratch/verify"

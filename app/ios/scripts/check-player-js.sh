#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fw="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
xcrun swiftc -F "$fw" -framework NuvxShared -Xlinker -rpath -Xlinker "$fw/NuvxShared.framework/Versions/A" -module-cache-path "$work/modules" -O -o "$work/verify" \
 "$repo/../../core/apple/src/App/YouTubePlayerJs.swift" \
 "$repo/../../core/apple/src/App/YouTubeChallengeSolver.swift" "$repo/scripts/player-js-verify/main.swift"
"$work/verify" "$repo/../../core/apple/resources/Resources/YouTubeSolver" "$@"

#!/bin/bash







set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

fw="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
verification_dir="$(mktemp -d)"
bin="$verification_dir/playback-verify"
xcrun swiftc -O -module-cache-path /tmp/nuvx-swift-module-cache -o "$bin" \
  -F "$fw" -framework NuvxShared -framework WebKit \
  -Xlinker -rpath -Xlinker "$fw/NuvxShared.framework/Versions/A" \
  "$repo/../../core/apple/src/App/YouTubeChallengeSolver.swift" \
  "$repo/../../core/apple/src/App/YouTubePlayerJs.swift" \
  "$repo/../../core/apple/src/App/ApplePoTokenProvider.swift" \
  "$here/playback-verify/main.swift"

exec "$bin" "$repo/../../core/apple/resources/Resources/YouTubeSolver"

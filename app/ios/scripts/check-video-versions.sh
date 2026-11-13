#!/bin/bash



set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"
fw="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
native_fw="$repo/../../core/apple/build/Frameworks/NativeCoreFFI.xcframework/macos-arm64"
verification_dir="$(mktemp -d)"
bin="$verification_dir/video-versions-verify"
mkdir -p "$verification_dir/home"
trap 'rm -rf "$verification_dir"' EXIT

if [ ! -d "$native_fw" ]; then
  echo "no NativeCoreFFI for macos-arm64 — run scripts/build-native-core.sh first" >&2
  exit 2
fi

xcrun swiftc -O -module-cache-path /tmp/nuvx-swift-module-cache -o "$bin" \
  -F "$native_fw" -framework NativeCoreFFI \
  -framework CoreAudio -framework AudioToolbox -framework CoreFoundation \
  -Xlinker -rpath -Xlinker "$native_fw/NativeCoreFFI.framework/Versions/A" \
  -F "$fw" -framework NuvxShared -framework WebKit -framework JavaScriptCore \
  -Xlinker -rpath -Xlinker "$fw/NuvxShared.framework/Versions/A" \
  "$repo/../../core/apple/src/App/YouTubeChallengeSolver.swift" \
  "$repo/../../core/apple/src/App/YouTubePlayerJs.swift" \
  "$repo/../../core/apple/src/App/ApplePoTokenProvider.swift" \
  "$repo/../../core/apple/src/App/InnertubeStreamResolver.swift" \
  "$repo/../../core/apple/src/App/ResolvedVideoSide.swift" \
  "$repo/../../core/apple/src/Generated/NativeCore.swift" \
  "$repo/../../core/apple/src/PlaybackSession/PlaybackDebugLog.swift" \
  "$repo/../../core/apple/src/PlaybackSession/VideoVersionAlignment.swift" \
  "$here/video-versions-verify/main.swift" || exit 2

HOME="$verification_dir/home" CFFIXED_USER_HOME="$verification_dir/home" \
  "$bin" "$repo/../../core/apple/resources/Resources/YouTubeSolver"
status=$?
exit "$status"

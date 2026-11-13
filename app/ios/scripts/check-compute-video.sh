#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
native="$repo/../../core/apple/build/Frameworks/NativeCoreFFI.xcframework/macos-arm64"
xcrun swiftc -O -parse-as-library -module-cache-path "$work/modules" -o "$work/verify" \
 -F "$native" -framework NativeCoreFFI -framework CoreAudio -framework AudioToolbox \
 "$repo/../../core/apple/src/Generated/NativeCore.swift" \
 "$repo/../../core/apple/src/PlaybackSession/MusicAnalysisQueue.swift" \
 "$repo/../../core/apple/src/PlaybackSession/AutomixOverlayCache.swift" \
 "$repo/../../core/apple/src/PlaybackSession/VideoSegmentIndex.swift" \
 "$repo/../../core/apple/src/PlaybackSession/VideoIndexCache.swift" \
 "$repo/../../core/apple/src/PlaybackSession/VideoRangeDelivery.swift" \
 "$repo/../../core/apple/src/PlaybackSession/VideoAudioDownloader.swift" \
 "$repo/scripts/compute-video-verify/main.swift"
"$work/verify"

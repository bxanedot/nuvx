#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
xcrun swiftc -parse-as-library -O -module-cache-path "$work/modules" -o "$work/verify" \
 "$repo/../../core/apple/src/PlaybackSession/MacAudioRoutes.swift" \
 "$repo/../../core/apple/src/PlaybackSession/HeadphoneRouting.swift" \
 "$repo/../../core/apple/src/PlaybackSession/PlaybackDebugLog.swift" "$repo/scripts/http-verify/routing.swift"
"$work/verify"

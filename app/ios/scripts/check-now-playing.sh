#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
xcrun swiftc -parse-as-library -module-cache-path /tmp/nuvx-swift-module-cache \
  -o "$scratch/now-playing-verify" \
  "$repo/../../core/apple/src/PlaybackSession/AudioSessionReadiness.swift" \
  "$repo/../../core/apple/src/PlaybackSession/NowPlayingLifecycle.swift" \
  "$repo/scripts/now-playing-verify/main.swift"
"$scratch/now-playing-verify"

#!/bin/bash








set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

bin="$(mktemp -d)/video-sync-verify"
xcrun swiftc -O -module-cache-path /tmp/nuvx-swift-module-cache -o "$bin" \
  "$repo/../../core/apple/src/PlaybackSession/VideoSyncPolicy.swift" \
  "$here/video-sync-verify/main.swift" || exit 2

exec "$bin"

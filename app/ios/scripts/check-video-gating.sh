#!/bin/bash








set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

bin="$(mktemp -d)/video-gating-verify"
xcrun swiftc -O -module-cache-path /tmp/nuvx-swift-module-cache -o "$bin" \
  "$repo/../../core/apple/src/App/ResolvedVideoSide.swift" \
  "$repo/../../core/apple/src/UI/VideoSurfaceState.swift" \
  "$here/video-gating-verify/main.swift" || exit 2

exec "$bin"

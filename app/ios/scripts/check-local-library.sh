#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
bin="$(mktemp -d)/local-library-verify"
xcrun swiftc -module-cache-path /tmp/nuvx-swift-module-cache -o "$bin" \
  "$repo/../../core/apple/src/PlaybackSession/LocalTrackOrder.swift" \
  "$repo/scripts/local-library-verify/main.swift"
"$bin"

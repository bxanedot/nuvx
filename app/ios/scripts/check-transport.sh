#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
bin="$(mktemp -d)/transport-verify"
xcrun swiftc -module-cache-path /tmp/nuvx-swift-module-cache -o "$bin" \
  "$repo/../../core/apple/src/PlaybackSession/PlaybackLoadSubmissionGate.swift" \
  "$repo/scripts/transport-verify/main.swift"
"$bin"

#!/bin/bash








set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

bin="$(mktemp -d)/transition-pacing-verify"
xcrun swiftc -O -module-cache-path /tmp/nuvx-swift-module-cache -o "$bin" \
  "$repo/../../core/apple/src/PlaybackSession/TransitionPacing.swift" \
  "$here/transition-pacing-verify/main.swift" || exit 2

exec "$bin"

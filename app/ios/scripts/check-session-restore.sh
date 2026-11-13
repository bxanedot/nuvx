#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d "${TMPDIR:-/tmp}/nuvx-session.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
xcrun swiftc -parse-as-library -swift-version 5 -target arm64-apple-macos15.0 \
  ../../core/apple/src/PlaybackSession/QueueEntry.swift \
  ../../core/apple/src/PlaybackSession/LastPlayed.swift \
  scripts/session-restore-verify/check.swift -o "$tmp/check"
"$tmp/check"

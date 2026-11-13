#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
check_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuvx-source-priority.XXXXXX")"
trap 'rm -rf "$check_dir"' EXIT
xcrun swiftc -parse-as-library -swift-version 5 -target arm64-apple-macos15.0 \
  ../../core/apple/src/PlaybackSession/PlaybackSourceRace.swift \
  ../../core/apple/src/PlaybackSession/PlaybackPreparationPolicy.swift \
  ../../core/apple/src/PlaybackSession/PlaybackSwapConfirmation.swift \
  scripts/source-priority-verify/check.swift -o "$check_dir/check"
"$check_dir/check"

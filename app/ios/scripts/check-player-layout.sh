#!/usr/bin/env bash














set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK_DIR="${TMPDIR:-/tmp}/nuvx-player-layout-check"

rm -rf "$CHECK_DIR"
mkdir -p "$CHECK_DIR"

xcrun swiftc \
  -sdk "$(xcrun --show-sdk-path --sdk macosx)" \
  -target arm64-apple-macos15.0 \
  -swift-version 5 \
  -o "$CHECK_DIR/check" \
  "$ROOT/../../core/apple/src/UI/PlayerLayout.swift" \
  "$HERE/player-layout-verify/main.swift"

"$CHECK_DIR/check"

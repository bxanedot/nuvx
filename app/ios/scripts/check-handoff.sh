#!/usr/bin/env bash
















set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK_DIR="${TMPDIR:-/tmp}/nuvx-handoff-check"

rm -rf "$CHECK_DIR"
mkdir -p "$CHECK_DIR"

xcrun swiftc \
  -sdk "$(xcrun --show-sdk-path --sdk macosx)" \
  -target arm64-apple-macos15.0 \
  -swift-version 5 \
  -o "$CHECK_DIR/check" \
  "$ROOT/../../core/apple/src/App/HandoffActivity.swift" \
  "$HERE/handoff-verify/main.swift"

status=0
"$CHECK_DIR/check" || status=1

if grep -q -- "- com.bxane.nuvx.playback" "$ROOT/mobile/project.yml"; then
  echo "  ok   the activity type is declared in NSUserActivityTypes"
else
  echo "  FAIL the activity type is not declared in mobile/project.yml"
  status=1
fi

exit "$status"

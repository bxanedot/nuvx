#!/usr/bin/env bash


















set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK_DIR="${TMPDIR:-/tmp}/nuvx-signin-check"

rm -rf "$CHECK_DIR"
mkdir -p "$CHECK_DIR"

xcrun swiftc \
  -module-cache-path "$CHECK_DIR/modules" \
  -sdk "$(xcrun --show-sdk-path --sdk macosx)" \
  -target arm64-apple-macos15.0 \
  -swift-version 5 \
  -o "$CHECK_DIR/check" \
  "$ROOT/../../core/apple/src/UI/SignInNavigation.swift" \
  "$ROOT/../../core/apple/src/UI/SignInUserAgent.swift" \
  "$ROOT/../../core/apple/src/UI/SignInCapture.swift" \
  "$HERE/signin-verify/main.swift" || exit 2



"$CHECK_DIR/check" "$ROOT"

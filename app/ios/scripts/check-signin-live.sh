#!/usr/bin/env bash












set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
HERE="$(cd "$(dirname "$0")" && pwd)"
FW="$ROOT/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
CHECK_DIR="${TMPDIR:-/tmp}/nuvx-signin-live-check"

rm -rf "$CHECK_DIR"
mkdir -p "$CHECK_DIR"

xcrun swiftc -O -o "$CHECK_DIR/check" \
  -sdk "$(xcrun --show-sdk-path --sdk macosx)" \
  -target arm64-apple-macos15.0 \
  -swift-version 5 \
  -framework WebKit -framework AppKit \
  -o "$CHECK_DIR/check" \
  "$ROOT/../../core/apple/src/UI/SignInUserAgent.swift" \
  "$HERE/signin-live-verify/main.swift" || exit 2

"$CHECK_DIR/check"

#!/usr/bin/env bash
















set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
APP="$ROOT/mobile"
FW="$ROOT/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
CHECK_DIR="${TMPDIR:-/tmp}/nuvx-listening-check"

if [ ! -d "$FW" ]; then
  echo "NuvxShared.xcframework is missing — run ./scripts/build-shared-framework.sh first." >&2
  exit 1
fi

rm -rf "$CHECK_DIR"
mkdir -p "$CHECK_DIR"

xcrun swiftc \
  -D DEBUG \
  -sdk "$(xcrun --show-sdk-path --sdk macosx)" \
  -target arm64-apple-macos15.0 \
  -swift-version 5 \
  -module-cache-path /tmp/nuvx-swift-module-cache \
  -F "$FW" \
  -framework NuvxShared \
  -Xlinker -rpath -Xlinker "$FW/NuvxShared.framework/Versions/A" \
  -o "$CHECK_DIR/check" \
  "$ROOT/../../core/apple/src/PlaybackSession/ListeningStore.swift" \
  "$ROOT/scripts/listening-verify/main.swift"

"$CHECK_DIR/check"

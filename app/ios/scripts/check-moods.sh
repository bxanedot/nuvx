#!/bin/bash













set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

fw="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
bin="$(mktemp -d)/moods-verify"
xcrun swiftc -O -o "$bin" \
  -F "$fw" -framework NuvxShared \
  -Xlinker -rpath -Xlinker "$fw/NuvxShared.framework/Versions/A" \
  "$here/moods-verify/main.swift" || exit 2

exec "$bin"

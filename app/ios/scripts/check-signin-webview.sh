#!/usr/bin/env bash

set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fw="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
xcrun swiftc -parse-as-library -module-cache-path "$work/modules" \
  -target arm64-apple-macos15.0 -swift-version 5 \
  -F "$fw" -framework NuvxShared \
  -Xlinker -rpath -Xlinker "$fw/NuvxShared.framework/Versions/A" \
  "$repo/../../core/apple/src/UI/SignInNavigation.swift" \
  "$repo/../../core/apple/src/UI/SignInUserAgent.swift" \
  "$repo/../../core/apple/src/UI/SignInCapture.swift" \
  "$repo/../../core/apple/src/UI/YtMusicLoginView.swift" \
  "$repo/../../core/apple/src/App/GuardedHTTP.swift" \
  "$repo/scripts/signin-webview-verify/main.swift" -o "$work/check"
"$work/check"

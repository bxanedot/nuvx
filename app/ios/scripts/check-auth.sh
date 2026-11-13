#!/bin/bash







set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"


grep -Fq 'kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly' "$repo/../../core/apple/src/App/Keychain.swift" || exit 2
if grep -Fq 'kSecAttrSynchronizable as String: true' "$repo/../../core/apple/src/App/Keychain.swift"; then
  echo 'Session credentials must stay on this device.' >&2
  exit 2
fi

fw="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
bin="$(mktemp -d)/auth-verify"
xcrun swiftc -O -o "$bin" \
  -F "$fw" -framework NuvxShared \
  -Xlinker -rpath -Xlinker "$fw/NuvxShared.framework/Versions/A" \
  "$repo/../../core/apple/src/PlaybackSession/QueueEntry.swift" \
  "$repo/../../core/apple/src/App/InnertubeSearch.swift" \
  "$repo/../../core/apple/src/UI/SignInNavigation.swift" \
  "$repo/../../core/apple/src/UI/SignInCapture.swift" \
  "$repo/../../core/apple/src/App/SharedSessionCredential.swift" \
  "$here/auth-verify/main.swift" || exit 2

exec "$bin"

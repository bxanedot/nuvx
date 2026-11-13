#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

python3 - "$repo" "$work" <<'PY'
import sys
from pathlib import Path
repo, work = map(Path, sys.argv[1:])
source = (repo / '../../core/apple/src/PlaybackSession/StreamFileCache.swift').read_text()
(work / 'CanvasFileCache.swift').write_text('import Foundation\nimport CryptoKit\n' + source[source.index('actor CanvasFileCache {'):])
PY
framework="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
xcrun swiftc -parse-as-library -O -module-cache-path /tmp/nuvx-swift-module-cache \
  -F "$framework" -framework NuvxShared \
  -Xlinker -rpath -Xlinker "$framework/NuvxShared.framework/Versions/A" \
  "$repo/../../core/apple/src/PlaybackSession/CanvasMediaRequest.swift" \
  "$work/CanvasFileCache.swift" "$repo/scripts/canvas-verify/main.swift" \
  -o "$work/verify"
"$work/verify"

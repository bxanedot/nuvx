#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT


python3 - "$repo" "$work" <<'PY'
from pathlib import Path
import sys
repo, work = map(Path, sys.argv[1:])
source = (repo / '../../core/apple/src/PlaybackSession/ModuleJsEngine.swift').read_text()
assert 'private final class Engine {' in source
(work / 'ModuleJsEngine.swift').write_text(source.replace('private final class Engine {', 'final class Engine {', 1))
cache = (repo / '../../core/apple/src/PlaybackSession/StreamFileCache.swift').read_text()
(work / 'DiskCache.swift').write_text('import Foundation\nimport CryptoKit\n' + cache[cache.index('enum DiskCache {'):])
PY
native="$repo/../../core/apple/build/Frameworks/NativeCoreFFI.xcframework/macos-arm64"
shared="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
xcrun swiftc -O -disable-sandbox -parse-as-library -module-cache-path "$work/modules" -o "$work/verify" \
 -F "$native" -framework NativeCoreFFI -F "$shared" -framework NuvxShared \
 -framework CoreAudio -framework AudioToolbox -framework JavaScriptCore \
 -Xlinker -rpath -Xlinker "$shared/NuvxShared.framework/Versions/A" \
 "$repo/../../core/apple/src/Generated/NativeCore.swift" "$work/ModuleJsEngine.swift" \
 "$repo/../../core/apple/src/PlaybackSession/LocalArtwork.swift" \
 "$repo/../../core/apple/src/PlaybackSession/OfflineArtworkStore.swift" \
 "$repo/../../core/apple/src/PlaybackSession/ArtworkRequests.swift" \
 "$repo/../../core/apple/src/PlaybackSession/QueueEntry.swift" \
 "$repo/../../core/apple/src/App/GuardedHTTP.swift" "$work/DiskCache.swift" \
 "$repo/../../core/apple/src/PlaybackSession/LocalLibrary.swift" \
 "$repo/../../core/apple/src/PlaybackSession/LocalTrackOrder.swift" \
 "$repo/../../core/apple/src/PlaybackSession/AutomixModelLoader.swift" \
 "$repo/scripts/memory-verify/main.swift"
MEMORY_FIXTURE="$repo/native-core/tests/fixtures/seek-tone.m4a" "$work/verify"

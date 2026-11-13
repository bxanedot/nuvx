#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
fixture_pid=''
trap 'cp "$WORK/server.log" /tmp/nuvx-download-fixture-last.log 2>/dev/null || true; if [ -n "$fixture_pid" ]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi; rm -rf "$WORK"' EXIT
python3 "$ROOT/scripts/downloads-verify/network_fixture.py" "$WORK/port" > "$WORK/server.log" 2>&1 &
fixture_pid=$!
for attempt in $(seq 1 100); do [ -f "$WORK/port" ] && break; sleep 0.05; done
test -f "$WORK/port"
export NUVX_DOWNLOAD_TEST_BASE_URL="http://127.0.0.1:$(cat "$WORK/port")"

python3 - "$ROOT" "$WORK" <<'PY'
import sys
from pathlib import Path
root, work = map(Path, sys.argv[1:])
(work/'QueueEntry.swift').write_text((root/'../../core/apple/src/PlaybackSession/QueueEntry.swift').read_text())
PY
NATIVE="$ROOT/../../core/apple/build/Frameworks/NativeCoreFFI.xcframework/macos-arm64"
SHARED="$ROOT/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
xcrun swiftc -module-cache-path "$WORK/modules" -parse-as-library -o "$WORK/verify" \
 -F "$NATIVE" -framework NativeCoreFFI -F "$SHARED" -framework NuvxShared \
 -framework CoreAudio -framework AudioToolbox \
 -Xlinker -rpath -Xlinker "$SHARED/NuvxShared.framework/Versions/A" \
 "$ROOT/../../core/apple/src/Generated/NativeCore.swift" "$WORK/QueueEntry.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/DownloadStore.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/DownloadActivity.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/AudioDownloadContainer.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/StreamFileCache.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/CanvasMediaRequest.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/FileDownloadTransport.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/LocalArtwork.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/OfflineArtworkStore.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/ArtworkRequests.swift" \
 "$ROOT/../../core/apple/src/App/GuardedHTTP.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/PlaybackDebugLog.swift" \
 "$ROOT/../../core/apple/src/PlaybackSession/PlaybackRegionStore.swift" \
 "$ROOT/scripts/downloads-verify/NetworkChecks.swift" \
 "$ROOT/scripts/downloads-verify/ArtworkChecks.swift" \
 "$ROOT/scripts/downloads-verify/main.swift"
"$WORK/verify"

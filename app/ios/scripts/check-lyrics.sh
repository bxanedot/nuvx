#!/bin/bash









set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

lyrics_pane="$repo/../../core/apple/src/UI/NowPlaying/LyricsPane.swift"
share_card="$repo/../../core/apple/src/UI/NowPlaying/LyricShareCard.swift"
if ! grep -Fq 'private let maxSharedCharacters = 250' "$lyrics_pane" ||
   ! grep -Fq 'selection.isEmpty || nextTotal <= maxSharedCharacters' "$lyrics_pane" ||
   ! grep -Fq 'lyricTextIsRightToLeft(line.text)' "$lyrics_pane" ||
   ! grep -Fq 'PHAssetCreationRequest.forAsset()' "$lyrics_pane" ||
   ! grep -Fq 'storyFormat: storyCardFormat' "$lyrics_pane" ||
   ! grep -Fq 'renderer.scale = 2' "$lyrics_pane" ||
   ! grep -Fq '.frame(width: 540, height: cardHeight' "$share_card" ||
   ! grep -Fq 'storyFormat ? 960 : nil' "$share_card" ||
   ! grep -Fq 'NSPhotoLibraryAddUsageDescription:' "$repo/mobile/project.yml"; then
  echo "FAIL: lyric sharing must cover its budget exception, 1080px/story formats, and Photos export" >&2
  exit 1
fi
echo "  ok  lyric sharing enforces its character budget and export formats"

fw="$repo/../../core/apple/build/Frameworks/NuvxShared.xcframework/macos-arm64"
bin="$(mktemp -d)/lyrics-verify"
xcrun swiftc -O -o "$bin" \
  -F "$fw" -framework NuvxShared \
  -Xlinker -rpath -Xlinker "$fw/NuvxShared.framework/Versions/A" \
  "$here/lyrics-verify/main.swift"

exec "$bin"

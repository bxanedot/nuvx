#!/bin/bash
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
require() {
  local file="$1" pattern="$2"
  if ! grep -Fq "$pattern" "$repo/$file"; then
    echo "missing artist-signature contract: $file: $pattern" >&2
    exit 1
  fi
}

require ../../core/apple/src/UI/SearchView.swift 'isCurrent: isCurrentlyPlaying(hit)'
require ../../core/apple/src/UI/SearchView.swift 'private func qualityBadge(_ raw: String?)'
require ../../core/apple/src/App/ArtistSignatureRepository.swift 'https://commons.wikimedia.org/w/api.php'
require ../../core/apple/src/App/ArtistSignatureRepository.swift 'iiurlwidth'
require ../../core/apple/src/App/ArtistSignatureRepository.swift 'parseResponse'
require ../../core/apple/src/UI/DetailView.swift 'artist_signatures_enabled'
require ../../core/apple/src/UI/DetailView.swift 'artist_signatures_wikimedia_url'
require ../../core/apple/src/UI/DetailView.swift 'Signed by '
require ../../core/apple/src/UI/DetailView.swift 'for delaySeconds in [0, 5, 15, 30]'
require mobile/tests/ArtistSignatureRepositoryTests.swift 'testParseSelectsMatchingSvgThumbnail'

binary="$(mktemp "${TMPDIR:-/tmp}/nuvx-artist-signature-tests.XXXXXX")"
trap 'rm -f "$binary"' EXIT
swiftc -parse-as-library \
  "$repo/../../core/apple/src/App/ArtistSignatureRepository.swift" \
  "$repo/mobile/tests/ArtistSignatureRepositoryTests.swift" \
  -o "$binary"
"$binary"

echo "artist signature and search source contracts passed"

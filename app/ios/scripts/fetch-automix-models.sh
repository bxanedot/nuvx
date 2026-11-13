#!/usr/bin/env bash











set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/native-core/fixtures/models"
mkdir -p "$DEST"


BEAT_URL="$ROOT/../../core/apple/resources/Resources/Models/beat_this.onnx"
BEAT_SHA="9f10e5efa37d3e120da2121141825fe54b23b487b32dd25a54f89039fd8f85cb"
BEAT_BYTES=23478460


VOCALS_URL="$ROOT/../../core/apple/resources/Resources/Models/vocals_umxhq_int8.onnx"
VOCALS_SHA="7eba6bebce0cc2e2d3b5c0e490a8074e66ce27f64170c6802b245740dcaf21ef"
VOCALS_BYTES=23209009

fetch() {
  local url="$1" out="$2" want_sha="$3" want_bytes="$4"
  if [[ -s "$out" ]]; then
    local got_sha got_bytes
    got_sha="$(shasum -a 256 "$out" | awk '{print $1}')"
    got_bytes="$(stat -f%z "$out")"
    if [[ "$got_sha" == "$want_sha" && "$got_bytes" == "$want_bytes" ]]; then
      echo "already present and verified: $out"
      return
    fi
  fi
  echo "copying bundled $(basename "$out")…"
  cp "$url" "$out.tmp"
  local got_sha got_bytes
  got_sha="$(shasum -a 256 "$out.tmp" | awk '{print $1}')"
  got_bytes="$(stat -f%z "$out.tmp")"
  if [[ "$got_sha" != "$want_sha" || "$got_bytes" != "$want_bytes" ]]; then
    rm -f "$out.tmp"
    echo "checksum or size mismatch for $(basename "$out")" >&2
    echo "  expected $want_bytes bytes, sha256 $want_sha" >&2
    echo "  got      $got_bytes bytes, sha256 $got_sha" >&2
    exit 1
  fi
  mv "$out.tmp" "$out"
  echo "verified $(basename "$out")"
}

fetch "$BEAT_URL" "$DEST/beat_this.onnx" "$BEAT_SHA" "$BEAT_BYTES"
fetch "$VOCALS_URL" "$DEST/vocals_umxhq_int8.onnx" "$VOCALS_SHA" "$VOCALS_BYTES"
ls -lh "$DEST"/*.onnx

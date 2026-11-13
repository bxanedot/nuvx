#!/bin/bash

















set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/.." && pwd)"

work="$(mktemp -d)"
fixture_dir="$work/fixtures"
mkdir -p "$fixture_dir"





mkdir -p "$work/coreml-src/BeatThisFinal0.mlpackage"
printf '{"dummy": true}' > "$work/coreml-src/BeatThisFinal0.mlpackage/Manifest.json"
/usr/bin/aa archive -a raw -d "$work/coreml-src" -o "$fixture_dir/coreml.aar" || {
  echo "could not build the Core ML archive fixture" >&2
  exit 2
}

port="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"

python3 "$here/models-verify/fixture.py" --dir "$fixture_dir" --port "$port" \
  > "$work/fixture.out" 2> "$work/fixture.err" &
fixture_pid=$!
cleanup() {
  kill "$fixture_pid" 2>/dev/null
  wait "$fixture_pid" 2>/dev/null
  rm -rf "$work"
}
trap cleanup EXIT



for _ in $(seq 1 200); do
  if curl -sf --max-time 1 "http://127.0.0.1:$port/count" > /dev/null; then break; fi
  sleep 0.1
done

if ! curl -sf --max-time 1 "http://127.0.0.1:$port/count" > /dev/null; then
  echo "the fixture server did not start" >&2
  cat "$work/fixture.err" >&2
  exit 2
fi

bin="$work/models-verify"
mkdir -p "$work/modulecache"




xcrun swiftc -O -disable-sandbox -module-cache-path "$work/modulecache" -o "$bin" \
  "$repo/../../core/apple/src/PlaybackSession/AutomixModelStore.swift" \
  "$here/models-verify/main.swift" || exit 2

MODELS_FIXTURE="http://127.0.0.1:$port" \
MODELS_FIXTURE_DIR="$fixture_dir" \
MODELS_LIVE="${MODELS_LIVE:-0}" \
"$bin"

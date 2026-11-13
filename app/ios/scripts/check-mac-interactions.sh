#!/usr/bin/env bash


set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
python3 "$repo/scripts/mac-interactions-verify/extract.py" "$repo" "$scratch/main.swift"
xcrun swiftc -parse-as-library -module-cache-path "$scratch/module-cache" \
  -o "$scratch/mac-interactions-verify" "$scratch/main.swift"
"$scratch/mac-interactions-verify"

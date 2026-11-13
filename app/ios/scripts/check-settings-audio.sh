#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scratch="$(mktemp -d "${TMPDIR:-/tmp}/nuvx-settings-audio.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT
xcrun swiftc -parse-as-library \
  ../../core/apple/src/UI/Settings/AudioSettingsCatalog.swift \
  ../../core/apple/src/UI/ReleaseIdentity.swift scripts/settings-audio-verify/check.swift -o "$scratch/check"
"$scratch/check"
python3 scripts/release-verify/check.py

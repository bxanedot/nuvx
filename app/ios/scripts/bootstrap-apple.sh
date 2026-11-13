#!/usr/bin/env bash


set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
[[ "$(uname -s)" == Darwin ]] || { echo "Apple frameworks require an Apple Silicon Mac with Xcode." >&2; exit 1; }
for tool in xcodebuild xcrun xcodegen cargo rustup cmake java python3; do
  command -v "$tool" >/dev/null || { echo "Missing prerequisite: $tool" >&2; exit 1; }
done
chmod +x gradlew scripts/*.sh
python3 scripts/check-nuvx-port.py
rustup target add aarch64-apple-darwin aarch64-apple-ios aarch64-apple-ios-sim
bash scripts/build-native-core.sh
bash scripts/build-shared-framework.sh
(cd mobile && xcodegen generate --spec project.yml)
echo "Ready: mobile/Nuvx.xcodeproj (iOS). macOS uses the Rust app in ../desktop."

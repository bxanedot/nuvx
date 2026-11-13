#!/usr/bin/env bash


set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"




if [ -z "${JAVA_HOME:-}" ]; then
  if ! java -version >/dev/null 2>&1; then
    JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    if [ -x "$JBR/bin/java" ]; then
      export JAVA_HOME="$JBR"
    else
      echo "error: no usable JDK found — install one or set JAVA_HOME" >&2
      exit 1
    fi
  fi
fi




export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-18.0}"
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-15.0}"



CFG="${CONFIG:-debug}"
VARIANT="$(printf '%s' "${CFG:0:1}" | tr '[:lower:]' '[:upper:]')${CFG:1}"
./gradlew ":shared:assembleNuvxShared${VARIANT}XCFramework"






SRC="shared/build/XCFrameworks/$CFG/NuvxShared.xcframework"
FRAMEWORKS="$ROOT/../../core/apple/build/Frameworks"
DEST="$FRAMEWORKS/NuvxShared.xcframework"
mkdir -p "$FRAMEWORKS"
rm -rf "$DEST"
cp -R "$SRC" "$DEST"
echo "OK: $DEST (configuration: $CFG)"

#!/bin/bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo"

if [ -z "${JAVA_HOME:-}" ] && ! java -version >/dev/null 2>&1; then
  JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [ -x "$JBR/bin/java" ]; then
    export JAVA_HOME="$JBR"
  else
    echo "error: no usable JDK found — install one or set JAVA_HOME" >&2
    exit 1
  fi
fi
./gradlew :shared:macosArm64Test
scripts/check-playback.sh

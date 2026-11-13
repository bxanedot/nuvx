#!/usr/bin/env bash







set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
: "${KOTLIN_HOME:?Set KOTLIN_HOME to a Kotlin CLI distribution}"
: "${TTML_TEST_CLASSPATH:?Supply JUnit 4, Hamcrest and kotlinx-serialization-core JVM jars}"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

main="$repo/shared/src/commonMain/kotlin/com/bxane/nuvx/data/lyrics"
tests="$repo/shared/src/commonTest/kotlin/com/bxane/nuvx/data/lyrics"
classpath="$KOTLIN_HOME/lib/kotlin-test.jar:$KOTLIN_HOME/lib/kotlin-test-junit.jar:$TTML_TEST_CLASSPATH"
"$KOTLIN_HOME/bin/kotlinc" \
  "$main/TtmlLyrics.kt" "$main/LyricLine.kt" "$main/LyricAlignments.kt" \
  "$main/LyricGaps.kt" "$main/SyllableRuns.kt" "$main/EnhancedLrc.kt" \
  "$main/LyricsMatching.kt" "$main/LyricsQuery.kt" \
  "$tests/TtmlDuetTest.kt" "$tests/TtmlMarkupTest.kt" \
  -cp "$classpath" -d "$scratch/tests.jar"
java -cp "$scratch/tests.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar:$classpath" \
  org.junit.runner.JUnitCore \
  com.bxane.nuvx.data.lyrics.TtmlDuetTest \
  com.bxane.nuvx.data.lyrics.TtmlMarkupTest

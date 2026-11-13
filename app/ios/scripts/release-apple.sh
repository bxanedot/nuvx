#!/usr/bin/env bash









set -euo pipefail

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
[[ $# -ge 1 ]] || { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
version="$1"; shift
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail 'version must look like 1.0.0'
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
output_dir="$repo_root/build/release"
skip_prereqs=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir) [[ $# -ge 2 ]] || fail '--output-dir needs a path'; output_dir="$2"; shift 2 ;;
    --skip-prereqs) skip_prereqs=1; shift ;;
    *) fail "Unknown option: $1" ;;
  esac
done

cd "$repo_root"
spec_version="$(sed -n 's/^ *MARKETING_VERSION: "\(.*\)"$/\1/p' mobile/project.yml | head -1)"
spec_build="$(sed -n 's/^ *CURRENT_PROJECT_VERSION: "\(.*\)"$/\1/p' mobile/project.yml | head -1)"
[[ "$spec_version" == "$version" ]] || fail "mobile/project.yml says $spec_version, not $version"
[[ "$spec_build" =~ ^[0-9]+$ ]] || fail 'mobile/project.yml has no numeric CURRENT_PROJECT_VERSION'
if ! git diff --quiet HEAD -- || [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
  fail 'the working tree has uncommitted changes; a release is built from a clean commit'
fi
revision="$(git rev-parse --short=12 HEAD)"
printf 'Building nuvx %s (build %s) at %s\n' "$version" "$spec_build" "$revision"

if [[ "$skip_prereqs" -eq 0 ]]; then

  NATIVE_TARGETS="aarch64-apple-darwin aarch64-apple-ios" scripts/build-native-core.sh
  CONFIG=release scripts/build-shared-framework.sh
  (cd mobile && xcodegen generate --spec project.yml)
fi
[[ -d mobile/Nuvx.xcodeproj ]] || fail 'mobile/Nuvx.xcodeproj is missing; run without --skip-prereqs'

mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
NUVX_BUILD_REVISION="$revision" scripts/package-ios-ipa.sh "$version" --output-dir "$output_dir" --overwrite

ipa="$output_dir/Nuvx-$version-build$spec_build-ios-unsigned.ipa"
[[ -f "$ipa" ]] || fail "expected IPA not found: $ipa"

printf '\niOS release file in %s:\n  %s\n' "$output_dir" "$(basename "$ipa")"
printf 'macOS is built separately from the Rust desktop app in app/desktop.\n'

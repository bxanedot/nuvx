#!/bin/zsh










set -u

platform=${1:-mac}
derived=${2:-}
lock=/tmp/nuvx-build.lock

case $platform in
    mac)
        cd "$(dirname "$0")/../../desktop" || exit 2
        ;;
    ios)
        cd "$(dirname "$0")/../mobile" || exit 2
        project=Nuvx.xcodeproj
        scheme=Nuvx
        destination="platform=iOS,id=${NUVX_IOS_DEVICE_ID:?Set NUVX_IOS_DEVICE_ID}"
        ;;
    ipad)
        cd "$(dirname "$0")/../mobile" || exit 2
        project=Nuvx.xcodeproj
        scheme=Nuvx
        destination="platform=iOS,id=${NUVX_IPAD_DEVICE_ID:?Set NUVX_IPAD_DEVICE_ID}"
        ;;
    *) echo "usage: $0 mac|ios|ipad [derived-data-dir]" >&2; exit 2 ;;
esac

waited=0
until mkdir "$lock" 2>/dev/null; do
    if [[ -n $(find "$lock" -maxdepth 0 -mmin +30 2>/dev/null) ]]; then
        echo "removing a stale build lock" >&2
        rmdir "$lock" 2>/dev/null
        continue
    fi
    (( waited == 0 )) && echo "waiting for the other build to finish…" >&2
    waited=1
    sleep 5
done
trap 'rmdir "$lock" 2>/dev/null' EXIT INT TERM

if [[ $platform == mac ]]; then
    [[ -n $derived ]] && export CARGO_TARGET_DIR="$derived"
    cargo build
    exit $?
fi

xcodegen generate --spec project.yml --quiet || exit 3

args=(-project "$project" -scheme "$scheme" -destination "$destination"
      -configuration Debug -allowProvisioningUpdates)
[[ -n ${DEVELOPMENT_TEAM:-} ]] && args+=("DEVELOPMENT_TEAM=$DEVELOPMENT_TEAM")
[[ -n $derived ]] && args+=(-derivedDataPath "$derived")

xcodebuild "${args[@]}" build -quiet 2>&1 | grep -E 'error:|warning: .*Nuvx/mobile|BUILD' | head -60
exit ${pipestatus[1]}

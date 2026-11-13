#!/bin/sh
set -e

bin=$1
version=$2
out=$3
[ -n "$bin" ] && [ -n "$version" ] && [ -n "$out" ] || { echo "usage: $0 <binary> <version> <out dir>"; exit 1; }
here=$(cd "$(dirname "$0")" && pwd)
icon="$here/../assets/icon/icon.png"
app="$out/nuvx.app"

rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$bin" "$app/Contents/MacOS/nuvx"
cp -R "$here/../../../THIRD_PARTY_LICENSES" "$app/Contents/Resources/THIRD_PARTY_LICENSES"
chmod 755 "$app/Contents/MacOS/nuvx"
sed "s/@VERSION@/$version/g" "$here/Info.plist" > "$app/Contents/Info.plist"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
set=$tmp/nuvx.iconset
mkdir "$set"
for spec in 16:16x16 32:16x16@2x 32:32x32 64:32x32@2x 128:128x128 256:128x128@2x 256:256x256; do
    sips -z "${spec%%:*}" "${spec%%:*}" "$icon" --out "$set/icon_${spec#*:}.png" >/dev/null
done
iconutil -c icns "$set" -o "$app/Contents/Resources/nuvx.icns"

codesign --force --sign - "$app"
echo "Made $app"

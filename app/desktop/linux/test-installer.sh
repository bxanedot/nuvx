#!/bin/sh
set -eu

package=$1
here=$(cd "$(dirname "$0")" && pwd)
[ -s "$package/LICENSE" ] || { echo "Missing bundled GPL license" >&2; exit 1; }
[ -s "$package/licenses/cargo-notices.txt" ] || { echo "Missing bundled Cargo dependency notices" >&2; exit 1; }
cmp "$here/../assets/licenses/cargo-notices.txt" "$package/licenses/cargo-notices.txt"
for license in "$here/../assets/fonts"/LICENSE-*.txt; do
    name=$(basename "$license")
    [ -s "$package/licenses/$name" ] || { echo "Missing bundled license: $name" >&2; exit 1; }
done
for license in "$here/../assets/licenses"/*.txt; do
    name=$(basename "$license")
    [ -s "$package/licenses/$name" ] || { echo "Missing bundled license: $name" >&2; exit 1; }
done
for license in LICENSE COPYRIGHT; do
    [ -s "$package/licenses/glib-$license.txt" ] || { echo "Missing vendored GLib notice: $license" >&2; exit 1; }
done
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
mkdir -p "$root/stubs"
cat > "$root/stubs/ldd" <<'EOF'
#!/bin/sh
exit 0
EOF
cat > "$root/stubs/ldconfig" <<'EOF'
#!/bin/sh
printf '%s\n' 'libayatana-appindicator3.so.1 => /lib/libayatana-appindicator3.so.1'
EOF
chmod 755 "$root/stubs/ldd" "$root/stubs/ldconfig"

for arch in x86_64 aarch64; do
    case $arch in
        x86_64) machine=x86-64 ;;
        aarch64) machine=aarch64 ;;
    esac
    bin="$root/$arch"
    mkdir -p "$bin"
    cat > "$bin/uname" <<EOF
#!/bin/sh
printf '%s\n' '$arch'
EOF
    chmod 755 "$bin/uname"
    if [ "$arch" = x86_64 ]; then
        # Representative previous per-user install: executable, launcher, settings and downloads.
        old_lib="$bin/home/.local/lib/nuvx"
        user_data="$bin/home/share/nuvx"
        user_config="$bin/home/.config/nuvx"
        mkdir -p "$old_lib" "$user_data/downloads" "$user_config" "$bin/home/.local/bin" "$bin/home/share/applications"
        printf 'previous nuvx executable\n' > "$old_lib/nuvx"
        printf '{"track-123":{"path":"/music/track.flac","duration_ms":123000,"loudness_db":null}}\n' > "$user_data/downloads.json"
        printf '[]\n' > "$user_data/lastfm_queue.json"
        printf 'wgpu\n' > "$user_data/renderer"
        printf 'downloaded music fixture\n' > "$user_data/downloads/track.dat"
        printf 'reduce_motion=1\n' > "$user_config/preferences.txt"
        cp "$here/nuvx.desktop" "$bin/home/share/applications/nuvx.desktop"
        ln -s "$old_lib/nuvx" "$bin/home/.local/bin/nuvx"
    fi
    PATH="$bin:$root/stubs:$PATH" HOME="$bin/home" XDG_DATA_HOME="$bin/home/share" sh "$package/install.sh"
    file -b "$bin/home/.local/lib/nuvx/nuvx" | grep -F "$machine" >/dev/null
    cmp "$package/LICENSE" "$bin/home/.local/lib/nuvx/LICENSE"
    cmp "$package/licenses/cargo-notices.txt" "$bin/home/.local/lib/nuvx/licenses/cargo-notices.txt"
    for license in "$here/../assets/fonts"/LICENSE-*.txt; do
        name=$(basename "$license")
        cmp "$license" "$bin/home/.local/lib/nuvx/licenses/$name"
    done
    for license in "$here/../assets/licenses"/*.txt; do
        name=$(basename "$license")
        cmp "$license" "$bin/home/.local/lib/nuvx/licenses/$name"
    done
    for license in LICENSE COPYRIGHT; do
        cmp "$here/../vendor/glib/$license" "$bin/home/.local/lib/nuvx/licenses/glib-$license.txt"
    done
    if [ "$arch" = x86_64 ]; then
        # An in-place upgrade must not erase the existing user profile or alter its app identity.
        printf '{"track-123":{"path":"/music/track.flac","duration_ms":123000,"loudness_db":null}}\n' | cmp - "$user_data/downloads.json"
        printf '[]\n' | cmp - "$user_data/lastfm_queue.json"
        printf 'wgpu\n' | cmp - "$user_data/renderer"
        printf 'downloaded music fixture\n' | cmp - "$user_data/downloads/track.dat"
        printf 'reduce_motion=1\n' | cmp - "$user_config/preferences.txt"
        [ "$(readlink "$bin/home/.local/bin/nuvx")" = "$old_lib/nuvx" ]
        [ ! -e "$old_lib/nuvx.new" ]
        upgraded="$bin/home/share/applications/nuvx.desktop"
        grep -Fx 'Name=nuvx' "$upgraded" >/dev/null
        grep -Fx 'Icon=nuvx' "$upgraded" >/dev/null
        grep -Fx 'StartupWMClass=nuvx' "$upgraded" >/dev/null
        grep -Fx "Exec=$old_lib/nuvx" "$upgraded" >/dev/null
    fi
done

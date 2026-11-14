#!/bin/sh
set -eu

package=$1
here=$(cd "$(dirname "$0")" && pwd)
[ -s "$package/LICENSE" ] || { echo "Missing bundled GPL license" >&2; exit 1; }
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
    PATH="$bin:$root/stubs:$PATH" HOME="$bin/home" XDG_DATA_HOME="$bin/home/share" sh "$package/install.sh"
    file -b "$bin/home/.local/lib/nuvx/nuvx" | grep -F "$machine" >/dev/null
    cmp "$package/LICENSE" "$bin/home/.local/lib/nuvx/LICENSE"
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
done

#!/bin/sh
set -eu

package=$1
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
done

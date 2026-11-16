#!/usr/bin/env bash
# Runs on the server as the unprivileged deploy user. Usage: remote.sh check | deploy <release> | rollback | backup
set -euo pipefail
ROOT="$HOME/nuvx-sync"
PORT=8787
DB="$ROOT/data/nuvx-sync.db"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"

check() {
  local bad=0
  [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" = "yes" ] || { echo "MISSING: lingering is off. An admin must run: sudo loginctl enable-linger $USER"; bad=1; }
  systemctl --user show-environment >/dev/null 2>&1 || { echo "MISSING: no systemd user session (fixed by enabling lingering, then reconnecting)"; bad=1; }
  command -v curl >/dev/null || { echo "MISSING: curl"; bad=1; }
  [ -w "$HOME" ] || { echo "MISSING: $HOME is not writable"; bad=1; }
  local free_mb; free_mb=$(df -Pm "$HOME" | awk 'NR==2 {print $4}')
  [ "$free_mb" -ge 1024 ] || { echo "MISSING: under 1 GB free disk ($free_mb MB)"; bad=1; }
  echo "user=$USER arch=$(uname -m) free_disk_mb=$free_mb mem_mb=$(free -m | awk '/^Mem:/ {print $2}') docker=$(command -v docker >/dev/null && echo present || echo absent)"
  return $bad
}

healthy() {
  for _ in $(seq 1 20); do
    curl -fsS -m 2 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

backup() {
  [ -f "$DB" ] && [ -x "$ROOT/current/node/bin/node" ] || { echo "no database yet, nothing to back up"; return 0; }
  local out="$ROOT/backups/$(date -u +%Y%m%dT%H%M%SZ).db"
  # VACUUM INTO writes a consistent snapshot while the service keeps running.
  "$ROOT/current/node/bin/node" --disable-warning=ExperimentalWarning -e \
    'new (require("node:sqlite").DatabaseSync)(process.argv[1]).exec(`VACUUM INTO \x27${process.argv[2]}\x27`)' "$DB" "$out"
  chmod 600 "$out"
  ls -1t "$ROOT/backups"/*.db | tail -n +15 | xargs -r rm -f
  echo "$out"
}

activate() {
  ln -sfn "$ROOT/releases/$1" "$ROOT/current.new"
  mv -T "$ROOT/current.new" "$ROOT/current"
  mkdir -p "$HOME/.config/systemd/user"
  cp "$ROOT/current/deploy/nuvx-sync.service" "$HOME/.config/systemd/user/nuvx-sync.service"
  systemctl --user daemon-reload
  systemctl --user enable nuvx-sync >/dev/null 2>&1
  systemctl --user restart nuvx-sync
}

case "${1:-}" in
  check) check ;;
  backup) backup ;;
  deploy)
    release="${2:?release id}"
    [[ "$release" =~ ^[0-9a-f]{7,40}$ ]] || { echo "bad release id"; exit 2; }
    [ -f "$ROOT/releases/$release/src/server.ts" ] || { echo "release $release was not uploaded"; exit 2; }
    check
    mkdir -p "$ROOT/data" "$ROOT/backups"
    backup
    previous=$(basename "$(readlink "$ROOT/current" 2>/dev/null || true)")
    activate "$release"
    if healthy; then
      [ -n "$previous" ] && [ "$previous" != "$release" ] && echo "$previous" > "$ROOT/previous"
      ls -1dt "$ROOT/releases"/*/ | tail -n +6 | xargs -r rm -rf
      echo "deployed $release"
    else
      echo "health check failed for $release"
      journalctl --user -u nuvx-sync -n 30 --no-pager || true
      if [ -n "$previous" ]; then activate "$previous"; healthy && echo "rolled back to $previous"; else systemctl --user stop nuvx-sync; fi
      exit 1
    fi ;;
  rollback)
    previous=$(cat "$ROOT/previous" 2>/dev/null || true)
    [ -n "$previous" ] && [ -d "$ROOT/releases/$previous" ] || { echo "no previous release to roll back to"; exit 1; }
    current=$(basename "$(readlink "$ROOT/current")")
    backup
    activate "$previous"
    healthy || { echo "previous release is unhealthy too"; exit 1; }
    echo "$current" > "$ROOT/previous"
    echo "rolled back to $previous" ;;
  *) echo "usage: remote.sh check | deploy <release> | rollback | backup"; exit 2 ;;
esac

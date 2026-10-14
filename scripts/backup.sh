#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=/opt/nexora
BACKUP_DIR=/opt/nexora/backups
LOCK=/run/lock/nexora-backup.lock
CONTAINER=nexora-minecraft
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$BACKUP_DIR/nexora-$STAMP.tar.gz"
TMP="$OUT.tmp"
mkdir -p "$BACKUP_DIR"
exec 9>"$LOCK"
flock -n 9 || exit 0

cd "$ROOT"
paused=0
cleanup() {
  if [[ "$paused" == 1 ]]; then docker unpause "$CONTAINER" >/dev/null 2>&1 || true; fi
  rm -f "$TMP"
}
trap cleanup EXIT

if [[ "$(docker inspect --format '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)" == "true" ]]; then
  # Flush chunks through the live Paper console, then freeze all writers for a consistent filesystem snapshot.
  python3 "$ROOT/scripts/minecraft_console.py" "$CONTAINER" "save-all flush" "Saved the game"
  docker pause "$CONTAINER" >/dev/null
  paused=1
fi

tar -czf "$TMP" \
  --exclude='./backups' --exclude='./minecraft/logs' \
  -C "$ROOT" \
  minecraft source \
  compose.yaml .env PLUGIN_MANIFEST.md README.md docs scripts
mv "$TMP" "$OUT"

if [[ "$paused" == 1 ]]; then
  docker unpause "$CONTAINER" >/dev/null
  paused=0
fi
trap - EXIT

(cd "$BACKUP_DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
tar -tzf "$OUT" >/dev/null
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'nexora-*.tar.gz' -mtime +7 -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'nexora-*.tar.gz.sha256' -mtime +7 -delete
